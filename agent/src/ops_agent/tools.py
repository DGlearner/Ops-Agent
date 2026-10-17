"""The read-only tools the model may call, all bound to one incident. Each returns compact JSON: only the fields
that help an analysis, so that the model's context holds a whole incident."""

import json
import logging
from dataclasses import dataclass
from datetime import timedelta
from typing import Any, Callable

from .contracts import Evidence
from .core_api import CoreApi, CoreApiError, parse_time

log = logging.getLogger(__name__)

MAX_RESULT_CHARS = 12_000
MAX_ALERTS = 30
MAX_MESSAGE_CHARS = 300


@dataclass(frozen=True)
class Tool:
    name: str
    description: str
    parameters: dict
    run: Callable[[dict], Any]

    def spec(self) -> dict:
        return {"type": "function",
                "function": {"name": self.name, "description": self.description, "parameters": self.parameters}}


class Toolbox:
    def __init__(self, core: CoreApi, incident: dict):
        self._core = core
        self.incident = incident
        self.incident_id = incident["incident_id"]
        self._alerts: list[dict] | None = None
        self._components: set[str] | None = None
        self._tools = {t.name: t for t in (
            Tool("get_incident_alerts",
                 "本 Incident 的全部告警，按时间排序：规则、产生告警的服务、分组字段（fields.dependency 是调用失败时的"
                 "被调用方）、次数与阈值、时间窗口、摘要，以及样本日志（event_id、时间、消息、trace_id）。",
                 _object({}), lambda args: self._incident_alerts()),
            Tool("get_incident_timeline",
                 "本 Incident 的时间线：何时打开、每条告警何时并入、状态变化。",
                 _object({}), lambda args: self._timeline()),
            Tool("get_topology",
                 "服务目录：每个服务或数据库是什么、依赖谁。用来判断故障沿依赖怎样传播。",
                 _object({}), lambda args: self._topology()),
            Tool("search_alerts",
                 "查本 Incident 前后一段时间内的所有告警（也包括不属于本 Incident 的），用来看同一时间别处有没有问题。"
                 "in_this_incident 标明是否属于本 Incident。",
                 _object({
                     "service": {"type": "string", "description": "只看这个服务的告警"},
                     "rule_id": {"type": "string", "description": "只看这条规则的告警"},
                     "minutes_before": {"type": "integer", "minimum": 0, "maximum": 120,
                                        "description": "Incident 打开前多少分钟，默认 30"},
                     "minutes_after": {"type": "integer", "minimum": 0, "maximum": 120,
                                       "description": "Incident 最后一条告警后多少分钟，默认 30"},
                 }), self._search_alerts),
            Tool("list_nearby_incidents",
                 "本 Incident 前后一小时内打开的其他 Incident，用来判断是不是同一时间有几个独立的问题。",
                 _object({}), lambda args: self._nearby_incidents()),
        )}

    def specs(self) -> list[dict]:
        return [t.spec() for t in self._tools.values()]

    def call(self, name: str, arguments: str) -> str:
        """Runs a tool and returns its result as JSON text for the model; errors are results too."""
        tool = self._tools.get(name)
        if tool is None:
            return _json({"error": f"没有工具 {name}"})
        try:
            args = json.loads(arguments) if arguments and arguments.strip() else {}
            if not isinstance(args, dict):
                raise ValueError("arguments must be an object")
        except ValueError as e:
            return _json({"error": f"参数不是 JSON 对象：{e}"})
        try:
            result = _json(tool.run(args))
        except CoreApiError as e:
            log.warning("tool %s failed: %s", name, e)
            return _json({"error": f"查询失败：{e}"})
        except (ValueError, TypeError) as e:
            return _json({"error": f"参数不对：{e}"})
        except Exception as e:  # noqa: BLE001 - a broken tool should cost the model one call, not the analysis
            log.exception("tool %s broke", name)
            return _json({"error": f"工具出错：{type(e).__name__}"})
        if len(result) > MAX_RESULT_CHARS:
            result = result[:MAX_RESULT_CHARS] + "…（结果过长，已截断）"
        return result

    def unknown_evidence(self, evidence: list[Evidence]) -> list[Evidence]:
        """Evidence the incident does not have; core-service would reject it."""
        alerts = self._alert_rows()
        alert_ids = {a["alert_id"].lower() for a in alerts}
        event_ids = {e.lower() for a in alerts for e in a.get("sample_event_ids", [])}
        event_ids |= {s["event_id"].lower() for a in alerts for s in a.get("samples", [])}
        unknown = []
        for e in evidence:
            known = {"alert": lambda: e.id.strip().lower() in alert_ids,
                     "event": lambda: e.id.strip().lower() in event_ids,
                     "topology": lambda: e.id.strip() in self._component_names(),
                     "incident": lambda: e.id.strip().lower() == str(self.incident_id).lower()}[e.kind]()
            if not known:
                unknown.append(e)
        return unknown

    def _alert_rows(self) -> list[dict]:
        if self._alerts is None:
            self._alerts = sorted(self._core.incident_alerts(self.incident_id), key=lambda a: a["created_at"])
        return self._alerts

    def _component_names(self) -> set[str]:
        if self._components is None:
            self._components = {n["name"] for n in self._core.topology()["nodes"]}
        return self._components

    def _incident_alerts(self) -> dict:
        alerts = self._alert_rows()
        shown = [{
            "alert_id": a["alert_id"], "rule_id": a["rule_id"], "severity": a["severity"],
            "service": a.get("service"), "group_values": a.get("group_values", {}), "count": a["count"],
            "threshold": a["threshold"], "window_start": a["window_start"], "window_end": a["window_end"],
            "summary": a["summary"], "created_at": a["created_at"],
            "samples": [{"event_id": s["event_id"], "timestamp": s["timestamp"],
                         "message": (s.get("message") or "")[:MAX_MESSAGE_CHARS], "trace_id": s.get("trace_id")}
                        for s in a.get("samples", [])],
        } for a in alerts[:MAX_ALERTS]]
        result: dict = {"alerts": shown}
        if len(alerts) > MAX_ALERTS:
            result["omitted"] = len(alerts) - MAX_ALERTS
        return result

    def _timeline(self) -> list[dict]:
        return [{"type": e["type"], "at": e["at"], **({"payload": e["payload"]} if e.get("payload") else {}),
                 **({"to_status": e["to_status"]} if e.get("to_status") else {})}
                for e in self._core.incident_timeline(self.incident_id)[:50]]

    def _topology(self) -> dict:
        topology = self._core.topology()
        depends: dict[str, list[str]] = {}
        for edge in topology["edges"]:
            depends.setdefault(edge["from"], []).append(edge["to"])
        return {"services": [{"name": n["name"], "kind": n["kind"], "description": n.get("description"),
                              "depends_on": depends.get(n["name"], [])} for n in topology["nodes"]]}

    def _search_alerts(self, args: dict) -> dict:
        before = timedelta(minutes=int(args.get("minutes_before", 30)))
        after = timedelta(minutes=int(args.get("minutes_after", 30)))
        if not timedelta(0) <= before <= timedelta(hours=2) or not timedelta(0) <= after <= timedelta(hours=2):
            raise ValueError("minutes_before 和 minutes_after 在 0 到 120 之间")
        start = parse_time(self.incident["opened_at"]) - before
        end = parse_time(self.incident["last_alert_at"]) + after
        mine = {a["alert_id"] for a in self._alert_rows()}
        alerts = self._core.alerts(start=start, end=end, service=args.get("service"), rule_id=args.get("rule_id"))
        return {"from": start.isoformat(), "to": end.isoformat(), "alerts": [{
            "alert_id": a["alert_id"], "rule_id": a["rule_id"], "service": a.get("service"),
            "group_values": a.get("group_values", {}), "summary": a["summary"], "created_at": a["created_at"],
            "in_this_incident": a["alert_id"] in mine} for a in alerts]}

    def _nearby_incidents(self) -> list[dict]:
        opened = parse_time(self.incident["opened_at"])
        return [{"incident_id": i["incident_id"], "this": i["incident_id"] == self.incident_id,
                 "title": i["title"], "status": i["status"], "subjects": i.get("subjects", []),
                 "shared_dependencies": i.get("shared_dependencies", []), "alert_count": i["alert_count"],
                 "opened_at": i["opened_at"], "last_alert_at": i["last_alert_at"]}
                for i in self._core.incidents(start=opened - timedelta(hours=1), end=opened + timedelta(hours=1))]


def incident_overview(incident: dict) -> dict:
    keys = ("incident_id", "title", "status", "severity", "services", "subjects", "shared_dependencies",
            "alert_count", "opened_at", "last_alert_at")
    return {k: incident[k] for k in keys if k in incident}


def _object(properties: dict) -> dict:
    return {"type": "object", "properties": properties, "additionalProperties": False}


def _json(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))
