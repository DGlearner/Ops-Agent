"""What the assistant may do, and how (docs/project-design.md §6.7, 操作卡片).

The model never changes anything. It may propose one of a few actions with its propose_action tool; the proposal is
checked here before anyone sees it (a known kind, its parameters against the kind's schema, an incident that was
fetched in this session, evidence that is that incident's own) and kept as a card: proposed, version 1.

Only the user's confirm runs it, naming the card's version, and only once: proposed -> executing is a compare-and-set
on status and version, so a double click or a retried request runs nothing twice and gets the same receipt back.
Right before running, everything is checked again against the incident as it is now; if its status or version moved
since the proposal, the action is not run (blocked): the user decided on what they saw then. core-service checks the
version once more itself (expected_version, 409 STALE_VERSION), so nothing slips in between.

The receipt is what happened, not what was meant to: core-service's answer as it was, and the incident read back
afterwards. succeeded only when the incident is in the state asked for; no answer from core-service is reconciled by
reading the incident (applied, not applied, or unknown). The next answer is written from the receipt.
"""

import asyncio
import hashlib
import json
import logging
import uuid
from collections.abc import Callable
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from typing import Any

import jsonschema

from ..core_api import CoreApi, CoreApiError, CoreWriter, WriteOutcome
from . import gaps as gap_rules
from .evidence import RefTable
from .memory import TERMINAL_ACTION, ChatStore

log = logging.getLogger(__name__)

STATUS_LABEL = {"open": "未处理", "acknowledged": "已确认", "investigating": "排查中", "mitigated": "已缓解",
                "resolved": "已解决", "closed": "已关闭"}
# core-service's IncidentStatus.canMoveTo, to turn down a proposal early with a reason the model understands;
# core-service still decides. Closing and reopening are left to people in the console.
LIFECYCLE = {"open": {"acknowledged", "investigating", "mitigated", "resolved"},
             "acknowledged": {"investigating", "mitigated", "resolved"},
             "investigating": {"mitigated", "resolved"},
             "mitigated": {"investigating", "resolved"},
             "resolved": set(), "closed": set()}
MAX_EVIDENCE = 6
MAX_PER_ANSWER = 2


@dataclass(frozen=True)
class ActionKind:
    name: str
    description: str
    params: dict
    risk: str
    # Changes the system being watched (docs/agent-design.md §5): run by core-service's executor, only from a
    # runbook whose preconditions were seen, and confirmed with the card's digest.
    remedial: bool = False

    def title(self, target_ref: str, params: dict) -> str:
        if self.name == "transition_incident":
            return f"把 {target_ref} 改为「{STATUS_LABEL[params['to_status']]}」"
        if self.name == "start_container":
            return f"启动 {params['name']} 容器"
        if self.name == "kill_transaction":
            return f"结束 MySQL 事务 {params['trx_id']}（线程 {params['thread_id']}）"
        if self.name == "set_switch":
            return f"{'打开' if params['on'] else '关闭'}降级开关 {params['name']}" + (
                f"（{params['minutes']} 分钟）" if params["on"] else "")
        return f"请后台 Agent 重新分析 {target_ref}"


KINDS = {
    "transition_incident": ActionKind(
        "transition_incident", "改 Incident 的状态（确认、排查中、已缓解、已解决），写进它的时间线",
        {"type": "object", "additionalProperties": False, "required": ["to_status"],
         "properties": {"to_status": {"enum": ["acknowledged", "investigating", "mitigated", "resolved"]},
                        "note": {"type": "string", "maxLength": 200}}},
        "medium"),
    "request_analysis": ActionKind(
        "request_analysis", "请后台的 Incident 分析 Agent 重新分析这个 Incident",
        {"type": "object", "additionalProperties": False, "properties": {}},
        "low"),
    "start_container": ActionKind(
        "start_container", "启动停止的容器（shop-mysql、shop-redis）",
        {"type": "object", "additionalProperties": False, "required": ["name"],
         "properties": {"name": {"enum": ["shop-mysql", "shop-redis"]}}},
        "medium", remedial=True),
    "kill_transaction": ActionKind(
        "kill_transaction", "结束 MySQL 里阻塞别人的长事务（参数来自探针看到的那个事务）",
        {"type": "object", "additionalProperties": False, "required": ["trx_id", "thread_id"],
         "properties": {"trx_id": {"type": "string", "pattern": "^[0-9]{1,20}$"},
                        "thread_id": {"type": "integer", "minimum": 1}}},
        "high", remedial=True),
    "set_switch": ActionKind(
        "set_switch", "打开或关闭订单服务的降级开关（payment.fast-fail、inventory.fast-fail）：止血，不是修复",
        {"type": "object", "additionalProperties": False, "required": ["name", "on", "minutes"],
         "properties": {"name": {"enum": ["payment.fast-fail", "inventory.fast-fail"]}, "on": {"type": "boolean"},
                        "minutes": {"type": "integer", "minimum": 1, "maximum": 60}}},
        "high", remedial=True),
}
# Must match the runbook's parameters; the rest (a switch's minutes) may be chosen.
FIXED_PARAMS = {"start_container": ("name",), "kill_transaction": ("trx_id", "thread_id"), "set_switch": ("name", "on")}


def digest(payload: dict) -> str:
    """As core-service computes it (remediation.Digest): sorted keys, no spaces, SHA-256."""
    text = json.dumps(payload, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
    return "sha256:" + hashlib.sha256(text.encode("utf-8")).hexdigest()


class ProposalError(ValueError):
    """The proposal is turned down; the message goes back to the model."""


class ActionNotFound(KeyError):
    pass


class ActionConflict(Exception):
    """409: the card is not at the version the user confirmed, or is being run."""

    def __init__(self, code: str, detail: str, action: dict):
        super().__init__(detail)
        self.code, self.detail, self.action = code, detail, action


def tool_spec() -> dict:
    """propose_action, as the model sees it."""
    kinds = "；".join(f"{k.name}：{k.description}" for k in KINDS.values())
    return {"type": "function", "function": {
        "name": "propose_action",
        "description": ("提出一个操作，生成卡片给用户确认；用户确认后系统才执行，你自己不能执行。可用的操作：" + kinds
                        + "。只在用户要求处理某个 Incident 时提出。evidence 写支持这个操作的告警、日志或探针编号，"
                        "必须属于这个 Incident。处置类操作（启动容器、结束事务、降级开关）只能按匹配到的 Runbook 提出，"
                        "前置条件要由探针看到，参数用 Runbook 选项里的。"),
        "parameters": {"type": "object", "additionalProperties": False, "required": ["kind", "incident", "evidence",
                                                                                   "reason"],
                       "properties": {
                           "kind": {"enum": list(KINDS)},
                           "incident": {"type": "string", "description": "Incident 的编号，如 I1"},
                           "params": {"type": "object",
                                      "description": "transition_incident：{\"to_status\": ..., \"note\": ...}；"
                                                     "request_analysis：{}；处置类：Runbook 选项给出的 params"},
                           "evidence": {"type": "array", "items": {"type": "string"}, "minItems": 1,
                                        "maxItems": MAX_EVIDENCE, "description": "如 [\"A1\", \"E2\"]"},
                           "reason": {"type": "string", "description": "为什么要这么做，一句话"}}}}}


class ActionDesk:
    def __init__(self, store: ChatStore, core: CoreApi, writer: CoreWriter | None, ttl_s: float = 900,
                 clock: Callable[[], datetime] = lambda: datetime.now(timezone.utc)):
        self._store = store
        self._core = core
        self._writer = writer
        self._ttl = timedelta(seconds=ttl_s)
        self._clock = clock

    @property
    def enabled(self) -> bool:
        return self._writer is not None

    # Proposing: the model's tool.

    async def propose(self, session_id: str, message_id: str, refs: RefTable, args: dict,
                      context: dict | None = None) -> dict:
        """context: what this answer worked out (chat.graph): the case, the runbook match, the probes' observations,
        the gaps and the operations the user's constraints rule out. A remediation needs all of it."""
        kind = KINDS.get(str(args.get("kind")))
        if kind is None:
            raise ProposalError(f"没有这种操作：{args.get('kind')}；可用的是 {'、'.join(KINDS)}")
        params = args.get("params") or {}
        try:
            jsonschema.validate(params, kind.params)
        except jsonschema.ValidationError as e:
            raise ProposalError(f"params 不对：{e.message}") from e
        target = refs.resolve(str(args.get("incident") or ""))
        if target is None or target.kind != "incident":
            raise ProposalError(f"{args.get('incident')} 不是查到过的 Incident 编号")
        evidence = self._evidence(refs, args.get("evidence"), target.id)
        reason = str(args.get("reason") or "").strip()[:200]
        if not reason:
            raise ProposalError("要写明 reason")
        incident = await asyncio.to_thread(self._core.incident, target.id)
        problem = _not_allowed(kind.name, params, incident)
        if problem:
            raise ProposalError(problem)
        extra = self._remedial(kind, params, target.id, context) if kind.remedial else {}
        for existing in self._store.actions(session_id):
            if (existing["status"] == "proposed" and existing["kind"] == kind.name
                    and existing["target"]["incident_id"] == target.id and existing["params"] == params):
                return existing
        now = self._clock()
        card = {"title": kind.title(target.ref, params), "params": params, "risk": kind.risk, "reason": reason,
                "target": {"ref": target.ref, "incident_id": target.id, "title": incident["title"],
                           "status": incident["status"], "version": incident["version"]},
                "evidence": evidence, **extra}
        action = {"action_id": str(uuid.uuid4()), "session_id": session_id, "message_id": message_id,
                  "kind": kind.name, "card": card, "created_at": _iso(now), "expires_at": _iso(now + self._ttl)}
        self._store.add_action(action)
        return self._store.action(action["action_id"])

    def _remedial(self, kind: ActionKind, params: dict, incident_id: str, context: dict | None) -> dict:
        """The checks a remediation must pass to be proposed at all, and what its card shows besides."""
        context = context or {}
        case, matched = context.get("case"), context.get("match")
        blocker = gap_rules.blocking(context.get("gaps") or [], kind.name)
        if blocker is not None:
            raise ProposalError(f"现在不能提出这个处置（{blocker.kind}）：{blocker.detail}")
        if case is None or matched is None:
            raise ProposalError("处置类操作要有处置单和匹配的 Runbook")
        if incident_id not in [str(i) for i in case.get("incident_ids") or []]:
            raise ProposalError("这个 Incident 不在当前的处置单里")
        option = next((o for o in context.get("options") or [] if o["op"] == kind.name), None)
        if option is None:
            raise ProposalError(f"Runbook {matched.runbook.id} 里没有 {kind.name}；它能做的是 "
                                + "、".join(r["op"] for r in matched.runbook.remediations))
        if not option["ready"]:
            raise ProposalError("前置条件不满足：" + "；".join(option["unmet"]))
        expected = option["params"] or {}
        wrong = [k for k in FIXED_PARAMS[kind.name] if params.get(k) != expected.get(k)]
        if wrong:
            raise ProposalError(f"参数要用 Runbook 选项里的：{json.dumps(expected, ensure_ascii=False)}")
        if kind.name in (context.get("forbidden") or {}):
            raise ProposalError(f"用户的限制排除了这个操作（{context['forbidden'][kind.name]}）")
        payload = {"case_id": case["case_id"], "incident_id": incident_id, "op": kind.name, "params": params}
        verify = "执行后约 2 分钟内的告警和日志：" + ("止血是否生效（订单直接取消）" if kind.name == "set_switch"
                                               and params.get("on") else "依赖方不再报错、订单恢复成功")
        return {"remedial": True,
                "case": {"case_id": case["case_id"], "state_version": case["state_version"], "status": case["status"]},
                "runbook": {"id": matched.runbook.id, "version": matched.runbook.version},
                "preconditions": option["met"], "effect": option["effect"], "blast_radius": option["blast_radius"],
                "rollback": option["rollback"], "verify": verify, "payload_digest": digest(payload)}

    def _evidence(self, refs: RefTable, given: Any, incident_id: str) -> list[dict]:
        if not isinstance(given, list) or not 1 <= len(given) <= MAX_EVIDENCE:
            raise ProposalError(f"evidence 要列出 1 到 {MAX_EVIDENCE} 个编号")
        shown, wrong = [], []
        for value in dict.fromkeys(str(v).strip().upper() for v in given):
            item = refs.resolve(value)
            if item is None:
                wrong.append(f"{value} 不在查到的数据里")
            elif item.kind == "incident":
                if item.id != incident_id:
                    wrong.append(f"{value} 是另一个 Incident")
            elif item.incident_id != incident_id:
                wrong.append(f"{value} 不属于这个 Incident")
            else:
                shown.append({"ref": item.ref, "kind": item.kind, "id": item.id, "label": item.label})
        if wrong:
            raise ProposalError("evidence 不对：" + "；".join(wrong))
        if not shown:
            raise ProposalError("evidence 至少要有一条这个 Incident 的告警或日志")
        return shown

    # Deciding: the user, through the API.

    async def confirm(self, session_id: str, action_id: str, version: int, payload_digest: str | None = None) -> dict:
        """payload_digest: the digest of the card the user saw; a remediation is confirmed only with it."""
        action = self._mine(session_id, action_id)
        if action["status"] in TERMINAL_ACTION:
            if _replay(action, version):
                return action  # the same confirm again: the same receipt, nothing run twice
            raise ActionConflict("ACTION_CLOSED", f"这个操作已经{action['status']}", action)
        if action["status"] == "executing":
            raise ActionConflict("ACTION_IN_PROGRESS", "这个操作正在执行", action)
        if action["version"] != version:
            raise ActionConflict("ACTION_CHANGED", f"卡片已更新到第 {action['version']} 版，请看过再确认", action)
        if action.get("remedial") and payload_digest != action.get("payload_digest"):
            raise ActionConflict("PAYLOAD_MISMATCH", "确认的不是这张卡片上的操作（摘要不一致），请刷新后再确认", action)
        now = self._clock()
        if now >= _time(action["expires_at"]):
            receipt = self._receipt(action, version, "expired", executed=False,
                                    summary="操作已过期，没有执行：提出时依据的数据可能已经变了，请重新提问")
            return self._finish(action, "proposed", version, receipt)
        if not self._store.move_action(action_id, "proposed", version, "executing"):
            return await self._lost_race(session_id, action_id, version)
        running = version + 1
        try:
            receipt = await self._run(action, version)
        except Exception as e:  # noqa: BLE001 - whatever happened, the card must not stay executing
            log.exception("chat: action %s failed unexpectedly", action_id)
            receipt = self._receipt(action, version, "unknown", executed=None,
                                    summary=f"执行时出了意外（{type(e).__name__}），不知道是否已生效；请在 Incident 详情里核对")
        return self._finish(action, "executing", running, receipt)

    async def decline(self, session_id: str, action_id: str, version: int) -> dict:
        action = self._mine(session_id, action_id)
        if action["status"] != "proposed" or action["version"] != version:
            if action["status"] == "declined":
                return action
            raise ActionConflict("ACTION_CHANGED", f"这个操作现在是 {action['status']}", action)
        receipt = self._receipt(action, version, "declined", executed=False, summary="你拒绝了这个操作，没有执行",
                                decision="decline")
        return self._finish(action, "proposed", version, receipt)

    def cards(self, session_id: str) -> list[dict]:
        return self._store.actions(session_id)

    def _mine(self, session_id: str, action_id: str) -> dict:
        action = self._store.action(action_id)
        if action is None or action["session_id"] != session_id:
            raise ActionNotFound(action_id)
        return action

    async def _lost_race(self, session_id: str, action_id: str, version: int) -> dict:
        """Someone else moved the card between the read and the compare-and-set: a second click, usually."""
        action = self._mine(session_id, action_id)
        if action["status"] in TERMINAL_ACTION and _replay(action, version):
            return action
        raise ActionConflict("ACTION_IN_PROGRESS" if action["status"] == "executing" else "ACTION_CHANGED",
                             "这个操作已经在处理", action)

    def _finish(self, action: dict, from_status: str, from_version: int, receipt: dict) -> dict:
        if not self._store.move_action(action["action_id"], from_status, from_version, receipt["status"], receipt):
            # Only this request holds an executing card; for the others, whoever got there first stands.
            log.warning("chat: action %s changed while it was being finished", action["action_id"])
        return self._store.action(action["action_id"])

    # Running.

    async def _run(self, action: dict, version: int) -> dict:
        target = action["target"]
        refs = RefTable((self._store.session(action["session_id"]) or {}).get("refs"))
        checks = []
        if action["kind"] not in KINDS:
            return self._blocked(action, version, checks, f"不认识的操作 {action['kind']}")
        try:
            jsonschema.validate(action["params"], KINDS[action["kind"]].params)
            checks.append("参数符合定义")
        except jsonschema.ValidationError as e:
            return self._blocked(action, version, checks, f"参数不对：{e.message}")
        for e in action["evidence"]:
            item = refs.resolve(e["ref"])
            if item is None or item.id != e["id"]:
                return self._blocked(action, version, checks, f"证据 {e['ref']} 和提出时的不一致")
        checks.append(f"{len(action['evidence'])} 条证据都属于 {target['ref']}")
        if self._writer is None:
            return self._blocked(action, version, checks, "这个对话服务没有开启执行操作")
        if action.get("remedial"):
            return await self._remediate(action, version, checks)
        try:
            incident = await asyncio.to_thread(self._core.incident, target["incident_id"])
        except CoreApiError as e:
            return self._blocked(action, version, checks, f"读不到 Incident 的当前状态（{e}）")
        if (incident["status"], incident["version"]) != (target["status"], target["version"]):
            return self._blocked(action, version, checks,
                                 f"{target['ref']} 在提出操作后被改过（{_state(target)} → {_state(incident)}），"
                                 "没有执行；请看过最新情况再决定")
        problem = _not_allowed(action["kind"], action["params"], incident)
        if problem:
            return self._blocked(action, version, checks, problem)
        checks.append(f"{target['ref']} 仍是{_state(incident)}")

        if action["kind"] == "transition_incident":
            to = action["params"]["to_status"]
            outcome = await asyncio.to_thread(self._writer.transition, target["incident_id"], to,
                                              note=action["params"].get("note"),
                                              expected_version=target["version"], action_id=action["action_id"])
        else:
            outcome = await asyncio.to_thread(self._writer.request_analysis, target["incident_id"])
        after = await self._read_back(target["incident_id"])
        status, summary = _judge(action, outcome, after)
        receipt = self._receipt(action, version, status, executed=outcome.status != 0 or None, summary=summary,
                                checks=checks, outcome=outcome)
        if after is not None:
            receipt["observed"] = {"status": after["status"], "version": after["version"]}
        return receipt

    async def _remediate(self, action: dict, version: int, checks: list[str]) -> dict:
        """core-service checks the digest, the case's state version and the lab's preconditions again, runs it once
        and reads the lab back; the receipt is its answer."""
        case = action["case"]
        checks.append(f"摘要 {action['payload_digest'][7:19]}… 与确认的一致")
        run = self._store.run_of_message(action["message_id"]) or {}
        outcome = await asyncio.to_thread(
            self._writer.remediate, action_id=action["action_id"], case_id=case["case_id"],
            incident_id=action["target"]["incident_id"], op=action["kind"], params=action["params"],
            payload_digest=action["payload_digest"], expected_case_version=case["state_version"],
            origin={"run_id": run.get("run_id"), "session_id": action["session_id"],
                    "snapshot_digest": run.get("snapshot_digest")})
        body = outcome.response if isinstance(outcome.response, dict) else {}
        if outcome.ok:
            core_status = body.get("status")
            status = {"executed": "succeeded", "failed": "failed", "blocked": "blocked"}.get(core_status, "unknown")
            summary = (body.get("receipt") or {}).get("summary") or f"core-service 记为 {core_status}"
            if status == "succeeded":
                summary += "；恢复情况看之后的验证结论（约 2 分钟），执行成功不等于故障已恢复"
            receipt = self._receipt(action, version, status, executed=status in ("succeeded", "failed") or None
                                    if status != "blocked" else False, summary=summary, checks=checks,
                                    outcome=outcome)
            receipt["remediation"] = {"remediation_id": body.get("remediation_id"), "status": core_status,
                                      "precheck": body.get("precheck"),
                                      "observed": (body.get("receipt") or {}).get("observed")}
            return receipt
        if outcome.status == 0:
            return self._receipt(action, version, "unknown", executed=None, checks=checks, outcome=outcome,
                                 summary=f"core-service 没有回应（{outcome.error}），不知道是否已执行；请到处置单核对")
        if outcome.code in ("STALE_VERSION", "PAYLOAD_MISMATCH", "CASE_CLOSED"):
            why = {"STALE_VERSION": "处置单在提出操作后有变化（事实、限制或决定变了），没有执行；请看过最新情况再决定",
                   "PAYLOAD_MISMATCH": "确认的内容和要执行的不一致，没有执行",
                   "CASE_CLOSED": "处置单已经结束，没有执行"}[outcome.code]
            return self._receipt(action, version, "blocked", executed=False, summary=f"没有执行：{why}",
                                 checks=checks, outcome=outcome)
        detail = body.get("detail")
        return self._receipt(action, version, "failed", executed=False, checks=checks, outcome=outcome,
                             summary=f"core-service 拒绝了：HTTP {outcome.status} {outcome.code or ''}"
                                     + (f"，{detail}" if detail else ""))

    async def _read_back(self, incident_id: str) -> dict | None:
        try:
            return await asyncio.to_thread(self._core.incident, incident_id)
        except CoreApiError as e:
            log.warning("chat: reading incident %s back after an action failed: %s", incident_id, e)
            return None

    def _blocked(self, action: dict, version: int, checks: list[str], why: str) -> dict:
        return self._receipt(action, version, "blocked", executed=False, summary=f"没有执行：{why}", checks=checks)

    def _receipt(self, action: dict, version: int, status: str, *, executed: bool | None, summary: str,
                 checks: list[str] | None = None, outcome: WriteOutcome | None = None,
                 decision: str = "confirm") -> dict:
        receipt: dict[str, Any] = {"receipt_id": str(uuid.uuid4()), "action_id": action["action_id"],
                                   "status": status, "executed": executed, "decision": decision,
                                   "confirmed_version": version,
                                   "summary": summary, "checks": checks or [], "at": _iso(self._clock())}
        if outcome is not None:
            receipt["request"] = {"method": outcome.method, "path": outcome.path, "body": outcome.body}
            receipt["response"] = {"status": outcome.status, "code": outcome.code,
                                   "body": _excerpt(outcome.response), **({"error": outcome.error}
                                                                          if outcome.error else {})}
        return receipt


def _judge(action: dict, outcome: WriteOutcome, after: dict | None) -> tuple[str, str]:
    """The receipt's status from what core-service answered and what the incident looks like afterwards."""
    target = action["target"]
    ref = target["ref"]
    if action["kind"] == "request_analysis":
        if outcome.ok and isinstance(outcome.response, dict) and outcome.response.get("analysis_id"):
            return "succeeded", f"已请后台 Agent 分析 {ref}（分析 {outcome.response['analysis_id'][:8]}，状态 " \
                                f"{outcome.response.get('status')}），结果出来后会显示在 Incident 详情里"
        if outcome.status == 0:
            return "unknown", f"core-service 没有回应（{outcome.error}），不知道分析是否已经派发"
        return "failed", f"core-service 拒绝了：HTTP {outcome.status} {outcome.code or ''}".strip()

    to = action["params"]["to_status"]
    if after is None:
        return ("unknown", f"core-service 返回 HTTP {outcome.status}，但之后读不到 {ref}，不能确认是否生效"
                if outcome.status else f"core-service 没有回应（{outcome.error}），之后也读不到 {ref}")
    applied = after["status"] == to and after["version"] > target["version"]
    if outcome.ok and applied:
        return "succeeded", f"已把 {ref} 改为「{STATUS_LABEL[to]}」（core-service 返回 {outcome.status}，" \
                            f"读回确认：{_state(after)}）"
    if outcome.ok:
        # core-service said yes, but the incident does not show it: report what is there, not what was meant.
        return "unknown", f"core-service 返回 {outcome.status}，但读回的 {ref} 是{_state(after)}，和要求的不一致"
    if outcome.status == 0:
        if applied:
            return "succeeded", f"core-service 没有回应（{outcome.error}），读回 {ref} 已是{_state(after)}，按已生效记"
        if after["version"] == target["version"]:
            return "failed", f"core-service 没有回应（{outcome.error}），{ref} 没有变化，没有生效"
        return "unknown", f"core-service 没有回应，{ref} 已被改成{_state(after)}，不能确认是不是这个操作改的"
    detail = outcome.response.get("detail") if isinstance(outcome.response, dict) else None
    return "failed", f"core-service 拒绝了：HTTP {outcome.status} {outcome.code or ''}" + (f"，{detail}" if detail else "")


def _replay(action: dict, version: int) -> bool:
    receipt = action.get("receipt") or {}
    return receipt.get("decision") == "confirm" and receipt.get("confirmed_version") == version


def _not_allowed(kind: str, params: dict, incident: dict) -> str | None:
    if KINDS[kind].remedial:
        return "已关闭的 Incident 不再处置" if incident["status"] == "closed" else None
    if kind == "transition_incident":
        status, to = incident["status"], params["to_status"]
        if to == status:
            return f"它已经是「{STATUS_LABEL[to]}」"
        if to not in LIFECYCLE.get(status, set()):
            return f"Incident 不能从「{STATUS_LABEL.get(status, status)}」改为「{STATUS_LABEL[to]}」"
    elif incident["status"] in ("resolved", "closed"):
        return "已解决或已关闭的 Incident 不再分析"
    return None


def _state(incident: dict) -> str:
    return f"「{STATUS_LABEL.get(incident['status'], incident['status'])}」（version {incident['version']}）"


def _excerpt(value: Any) -> Any:
    if isinstance(value, dict):
        keep = ("incident_id", "status", "version", "analysis_id", "remediation_id", "code", "detail")
        return {k: value[k] for k in keep if k in value}
    text = value if isinstance(value, str) else json.dumps(value, ensure_ascii=False)
    return (text or "")[:300]


def _iso(t: datetime) -> str:
    return t.astimezone(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def _time(value: str) -> datetime:
    return datetime.fromisoformat(value.replace("Z", "+00:00"))
