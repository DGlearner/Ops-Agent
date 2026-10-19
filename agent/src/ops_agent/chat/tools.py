"""What the chat can look at, all through core-service's read-only API. The orchestration calls these directly to
fetch the evidence up front; the model gets four of them as tools to look further. Everything the model sees carries
refs (I1, A2, E3) instead of ids, and times in the user's time zone."""

import asyncio
import json
import logging
from collections.abc import Callable
from dataclasses import dataclass
from datetime import datetime, timedelta
from typing import Any
from zoneinfo import ZoneInfo

from ..baseline import rule_attribution
from ..core_api import CoreApi, CoreApiError, parse_time
from .evidence import RefTable

log = logging.getLogger(__name__)

# Alerts per incident in the evidence the orchestration fetches; get_incident lists up to ALL_ALERTS.
MAX_ALERTS = 8
ALL_ALERTS = 30
MAX_SAMPLES = 2
MAX_MESSAGE_CHARS = 300
MAX_RESULT_CHARS = 12_000
SEVERITY_RANK = {"critical": 4, "high": 3, "medium": 2, "low": 1, "info": 0}


@dataclass(frozen=True)
class ToolResult:
    content: str
    summary: str
    ok: bool = True


class ChatTools:
    def __init__(self, core: CoreApi, refs: RefTable, now: datetime, tz: ZoneInfo):
        self._core = core
        self.refs = refs
        self.now = now
        self._tz = tz
        self._topology: dict | None = None

    # The orchestration's fetches.

    async def topology(self) -> dict:
        if self._topology is None:
            self._topology = await asyncio.to_thread(self._core.topology)
        return self._topology

    async def incident(self, incident_id: str) -> dict:
        return await asyncio.to_thread(self._core.incident, incident_id)

    async def recent_incidents(self, minutes: int, services: tuple[str, ...] = (), status: str | None = None,
                               limit: int = 10) -> list[dict]:
        """Incidents with an alert in the last minutes, the latest alert first, then the more severe. Incidents
        stay open until someone closes them, so it is the last alert that counts, not the opening."""
        since = self.now - timedelta(minutes=minutes)
        rows = await asyncio.to_thread(self._core.incidents, start=since - timedelta(days=1),
                                       end=self.now + timedelta(minutes=1), status=status, limit=100)
        rows = [i for i in rows if parse_time(i["last_alert_at"]) >= since]
        if services:
            rows = [i for i in rows if set(services) & _involved(i)]
        rows.sort(key=lambda i: (i["last_alert_at"][:16], SEVERITY_RANK.get(i["severity"], 0), i["last_alert_at"]),
                  reverse=True)
        return rows[:limit]

    async def recent_alerts(self, minutes: int, service: str | None = None, rule_id: str | None = None,
                            limit: int = 15) -> list[dict]:
        alerts = await asyncio.to_thread(self._core.alerts, start=self.now - timedelta(minutes=minutes),
                                         end=self.now + timedelta(minutes=1), service=service, rule_id=rule_id,
                                         limit=limit)
        return [self._alert(a, None, samples=0) for a in alerts]

    async def detail(self, incident: dict, max_alerts: int = MAX_ALERTS) -> dict:
        """An incident with its alerts and samples, what the correlation rules point at, and the background
        analysis if the agent has made one."""
        incident_id = incident["incident_id"]
        alerts, timeline, analyses = await asyncio.gather(
            asyncio.to_thread(self._core.incident_alerts, incident_id),
            asyncio.to_thread(self._core.incident_timeline, incident_id),
            asyncio.to_thread(self._core.analyses, incident_id))
        alerts = sorted(alerts, key=lambda a: a["created_at"])
        result = self.brief(incident)
        result["rule_attribution"] = [{"component": h.component, "basis": h.cause}
                                      for h in rule_attribution(incident, alerts)]
        completed = next((a for a in analyses if a.get("status") == "completed"), None)
        if completed and completed.get("model") != "baseline":
            result["background_analysis"] = {
                "model": completed.get("model"), "summary": completed.get("summary"),
                "uncertain": completed.get("uncertain", False),
                "causes": [{"component": h["component"], "confidence": h["confidence"]}
                           for h in completed.get("hypotheses") or []]}
        result["status_changes"] = [{"to": e["to_status"], "at": self.local(e["at"])}
                                    for e in timeline if e["type"] == "STATUS_CHANGED" and e.get("to_status")]
        # The first ones in full; past MAX_ALERTS one sample each, to keep the answer short.
        result["alerts"] = [self._alert(a, incident_id, samples=MAX_SAMPLES if n < MAX_ALERTS else 1)
                            for n, a in enumerate(alerts[:max_alerts])]
        if len(alerts) > max_alerts:
            result["omitted_alerts"] = len(alerts) - max_alerts
        return result

    def brief(self, incident: dict) -> dict:
        ref = self.refs.ref("incident", incident["incident_id"], incident["title"])
        return {"ref": ref, "title": incident["title"], "status": incident["status"], "severity": incident["severity"],
                "services": incident.get("services", []), "subjects": incident.get("subjects", []),
                "shared_dependencies": incident.get("shared_dependencies", []), "alert_count": incident["alert_count"],
                "opened_at": self.local(incident["opened_at"]), "last_alert_at": self.local(incident["last_alert_at"]),
                "minutes_since_last_alert": self.minutes_since(incident["last_alert_at"])}

    def local(self, timestamp: str) -> str:
        t = parse_time(timestamp).astimezone(self._tz)
        return t.strftime("%H:%M:%S") if t.date() == self.now.astimezone(self._tz).date() else t.strftime(
            "%m-%d %H:%M:%S")

    # The model's tools.

    def specs(self) -> list[dict]:
        return [_spec(name, description, properties, required)
                for name, (description, properties, required, _) in self._tools().items()]

    async def call(self, name: str, arguments: str) -> ToolResult:
        """Runs a tool for the model; errors are results too, so a bad call costs the model one call."""
        tool = self._tools().get(name)
        if tool is None:
            return _error(f"没有工具 {name}")
        try:
            args = json.loads(arguments) if arguments and arguments.strip() else {}
            if not isinstance(args, dict):
                raise ValueError("参数要是 JSON 对象")
            value, summary = await tool[3](args)
        except CoreApiError as e:
            log.warning("chat tool %s failed: %s", name, e)
            return _error(f"查询失败：{e}")
        except (ValueError, TypeError, KeyError) as e:
            return _error(f"参数不对：{e}")
        content = json.dumps(value, ensure_ascii=False, separators=(",", ":"))
        if len(content) > MAX_RESULT_CHARS:
            content = content[:MAX_RESULT_CHARS] + "…（结果过长，已截断）"
        return ToolResult(content, summary)

    def _tools(self) -> dict[str, tuple[str, dict, list[str], Callable[[dict], Any]]]:
        minutes = {"type": "integer", "minimum": 1, "maximum": 10080, "description": "往前看多少分钟，默认 60"}
        return {
            "list_incidents": ("最近有告警的 Incident（告警关联成的故障），最紧急的在前。",
                               {"minutes": minutes, "service": {"type": "string", "description": "只看涉及这个组件的"}},
                               [], self._list_incidents),
            "get_incident": (f"一个 Incident 的详情：告警（最多 {ALL_ALERTS} 条）和样本日志、规则归因、后台分析、状态变化。"
                             "已查到的数据里有 omitted_alerts 时，用它看没列出的告警。",
                             {"incident": {"type": "string", "description": "Incident 的编号，如 I1"}},
                             ["incident"], self._get_incident),
            "search_alerts": ("最近的告警（包括不属于已列出 Incident 的），新的在前。",
                              {"minutes": minutes, "service": {"type": "string", "description": "只看这个服务的告警"},
                               "rule_id": {"type": "string", "description": "只看这条规则的告警"}},
                              [], self._search_alerts),
            "get_topology": ("服务目录：每个组件是什么、依赖谁。", {}, [], self._get_topology),
        }

    async def _list_incidents(self, args: dict) -> tuple[Any, str]:
        services = (args["service"],) if args.get("service") else ()
        rows = await self.recent_incidents(_minutes(args), services)
        return [self.brief(i) for i in rows], f"找到 {len(rows)} 个 Incident"

    async def _get_incident(self, args: dict) -> tuple[Any, str]:
        incident_id = self.refs.id_of(str(args["incident"]), "incident")
        detail = await self.detail(await self.incident(incident_id), ALL_ALERTS)
        return detail, f"{detail['ref']}：{detail['alert_count']} 条告警"

    async def _search_alerts(self, args: dict) -> tuple[Any, str]:
        alerts = await self.recent_alerts(_minutes(args), args.get("service"), args.get("rule_id"))
        return alerts, f"找到 {len(alerts)} 条告警"

    async def _get_topology(self, args: dict) -> tuple[Any, str]:
        topology = await self.topology()
        depends: dict[str, list[str]] = {}
        for edge in topology["edges"]:
            depends.setdefault(edge["from"], []).append(edge["to"])
        services = [{"name": n["name"], "kind": n["kind"], "description": n.get("description"),
                     "depends_on": depends.get(n["name"], [])} for n in topology["nodes"]]
        return services, f"{len(services)} 个组件"

    def _alert(self, alert: dict, incident_id: str | None, samples: int = MAX_SAMPLES) -> dict:
        ref = self.refs.ref("alert", alert["alert_id"], alert["summary"], incident_id)
        shown = {"ref": ref, "rule_id": alert["rule_id"], "severity": alert["severity"], "service": alert.get("service"),
                 "count": alert["count"], "threshold": alert["threshold"], "summary": alert["summary"],
                 "at": self.local(alert["created_at"])}
        dependency = (alert.get("group_values") or {}).get("fields.dependency")
        if dependency:
            shown["dependency"] = dependency
        if samples:
            shown["samples"] = [{
                "ref": self.refs.ref("event", s["event_id"], s.get("message") or "", incident_id),
                "at": self.local(s["timestamp"]), "message": (s.get("message") or "")[:MAX_MESSAGE_CHARS]}
                for s in alert.get("samples", [])[:samples]]
        return shown

    def minutes_since(self, timestamp: str) -> int:
        """Whole minutes from then to now. Not "still going on": alerts come per window and repeats are suppressed,
        so a few quiet minutes do not mean the problem is over, and the model is told so."""
        return max(0, int((self.now - parse_time(timestamp)).total_seconds() // 60))


def _involved(incident: dict) -> set[str]:
    return {*incident.get("services", []), *incident.get("subjects", []), *incident.get("shared_dependencies", [])}


def _minutes(args: dict) -> int:
    minutes = int(args.get("minutes") or 60)
    if not 1 <= minutes <= 10080:
        raise ValueError("minutes 在 1 到 10080 之间")
    return minutes


def _spec(name: str, description: str, properties: dict, required: list[str]) -> dict:
    return {"type": "function", "function": {"name": name, "description": description, "parameters": {
        "type": "object", "properties": properties, "required": required, "additionalProperties": False}}}


def _error(message: str) -> ToolResult:
    return ToolResult(json.dumps({"error": message}, ensure_ascii=False), message, ok=False)
