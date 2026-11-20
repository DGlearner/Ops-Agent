"""Scoped, nonblocking delegation to the existing Incident worker. No remediation or forced retry here.

The DB-backed ensure endpoint owns concurrency and retry limits. This per-answer desk also remembers attempts
(including transport failures), so model loops cannot resubmit. Polling in the UI is strictly read-only.
"""

import asyncio
import re

from ..core_api import CoreWriter, parse_time

REANALYSE = re.compile(r"重新分析|重新诊断|再分析|重跑|reanaly[sz]|rerun", re.I)
LABEL = {"pending": "等待或执行中", "completed": "已完成", "failed": "失败", "timed_out": "超时"}


def evidence_at(incident: dict, raw: dict) -> str:
    # Arrival time catches late alerts with old event timestamps; status transitions are not new evidence.
    times = [e["at"] for e in raw["timeline"] if e.get("type") in {"OPENED", "ALERT_ATTACHED"}]
    times = times or [a["stored_at"] for a in raw["alerts"] if a.get("stored_at")] or [incident["last_alert_at"]]
    return max(times, key=parse_time)


def latest(incident: dict, raw: dict) -> dict | None:
    rows = [a for a in raw["analyses"] if a.get("incident_id") == incident["incident_id"] and a.get("analysis_id")]
    # A late completion never makes an older request the newest task.
    return max(rows, key=lambda a: (a.get("requested_at") or "", a["analysis_id"]), default=None)


def applicable(incident: dict, raw: dict, analysis: dict) -> bool:
    requested = analysis.get("requested_at")
    return bool(requested and parse_time(requested) >= parse_time(evidence_at(incident, raw)))


def task_view(incident: dict, raw: dict, analysis: dict, ref: str) -> dict:
    return {k: v for k, v in {
        "incident_id": incident["incident_id"], "incident_ref": ref, "analysis_id": analysis["analysis_id"],
        "status": analysis["status"], "requested_at": analysis.get("requested_at"),
        "evidence_at": evidence_at(incident, raw), "stale": not applicable(incident, raw, analysis),
    }.items() if v is not None}


def tool_spec() -> dict:
    return {"type": "function", "function": {
        "name": "delegate_diagnosis",
        "description": "当前事件需要复杂调查、已有分析不适用时委托后台只读诊断。先用只读工具补查能直接获取的数据。"
                       "复用已有任务，不等待完成；不能强制重跑同批证据的分析（重跑仍需确认卡片）。",
        "parameters": {"type": "object", "additionalProperties": False, "required": ["incident", "reason"],
                       "properties": {"incident": {"type": "string", "description": "本轮已读取的 Incident 编号，如 I1"},
                                      "reason": {"type": "string", "minLength": 1, "maxLength": 300}}}}}


class DiagnosisDesk:
    def __init__(self, writer: CoreWriter | None):
        self.writer = writer
        self.scope: dict[str, tuple[dict, dict]] = {}
        self.attempts: dict[str, tuple[dict | None, str]] = {}

    def observe(self, ref: str, incident: dict, raw: dict) -> dict | None:
        self.scope[ref] = incident, raw
        found = latest(incident, raw)
        return task_view(incident, raw, found, ref) if found else None

    async def delegate(self, ref: str, reason: str) -> tuple[dict | None, str]:
        if ref not in self.scope or not isinstance(reason, str) or not reason.strip() or len(reason) > 300:
            raise ValueError("需要本轮已读取的 Incident 编号和调查理由")
        if ref in self.attempts:
            return self.attempts[ref]
        incident, raw = self.scope[ref]
        found = latest(incident, raw)
        pending = next((a for a in raw["analyses"] if a.get("incident_id") == incident["incident_id"]
                        and a.get("status") == "pending"), None)
        if pending:
            result = task_view(incident, raw, pending, ref), "复用已有任务：等待或执行中；本轮不会等待后台完成。"
        elif found and applicable(incident, raw, found):
            result = task_view(incident, raw, found, ref), (
                f"同批证据的分析{LABEL.get(found['status'], found['status'])}；不会自动重复分析。"
                "完成结果请结合原始证据解释；失败、超时或结论不适用时，补查或提出重新分析确认卡片。")
        elif (not raw["alerts"] or not (any(a.get("samples") for a in raw["alerts"])
                                       or any(got.get("errors") for got in raw.get("logs", {}).values()))):
            result = None, "当前告警证据不足，不提交诊断；重新分析不保证产生新证据，也不能据此断言系统正常。"
        elif self.writer is None:
            result = None, "此实例未启用后台诊断调度。"
        else:
            # Cache uncertainty before the HTTP request as well: a lost acknowledgement is not a retry license.
            self.attempts[ref] = None, "后台请求结果未知，请查询任务状态；不会在本轮重试。"
            outcome = await asyncio.to_thread(self.writer.ensure_analysis, incident["incident_id"])
            if outcome.status == 204:
                result = None, "当前事件不符合自动分析条件（无事件证据、事件已结束或达到自动分析上限）。可补查或提出确认卡片。"
            elif (outcome.ok and isinstance(outcome.response, dict)
                  and outcome.response.get("incident_id") == incident["incident_id"]
                  and outcome.response.get("analysis_id")
                  and outcome.response.get("status") in LABEL):
                found = outcome.response
                raw["analyses"] = [found, *[a for a in raw["analyses"] if a.get("analysis_id") != found["analysis_id"]]]
                result = task_view(incident, raw, found, ref), (
                    f"后台任务{LABEL[found['status']]}；已展示任务身份，本轮不等待完成；完成后任务卡显示结果与证据。")
            else:
                result = None, "后台请求失败或回执不匹配，未确认任务创建；不会在本轮重试。请查询当前任务。"
        self.attempts[ref] = result
        return result
