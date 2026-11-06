"""For the chat tests: a fake core-service whose incidents happen now (the chat looks back from the present), and a
scripted streaming model."""

import copy
import json
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone

import httpx

from ops_agent.chat.llm import Turn
from ops_agent.core_api import CoreApi, CoreWriter
from ops_agent.llm import ToolCall

MYSQL_INCIDENT = "0199f000-0000-7000-8000-000000000011"
OLD_INCIDENT = "0199f000-0000-7000-8000-000000000012"
DB_ALERT = "0199f000-0000-7000-8000-0000000000c1"
RECOVERY_ALERT = "0199f000-0000-7000-8000-0000000000c2"
STRAY_ALERT = "0199f000-0000-7000-8000-0000000000c3"
DB_EVENT = "0199f000-0000-7000-8000-0000000000d1"
RECOVERY_EVENT = "0199f000-0000-7000-8000-0000000000d2"
LOG_EVENT = "0199f000-0000-7000-8000-0000000000d3"
PAYMENT_INCIDENT = "0199f000-0000-7000-8000-000000000013"
PAYMENT_ALERT = "0199f000-0000-7000-8000-0000000000c4"
PAYMENT_EVENT = "0199f000-0000-7000-8000-0000000000d4"
MOVES = {"open": {"acknowledged", "investigating", "mitigated", "resolved"},
         "acknowledged": {"investigating", "mitigated", "resolved"}, "investigating": {"mitigated", "resolved"},
         "mitigated": {"investigating", "resolved"}, "resolved": {"open", "closed"}, "closed": set()}
RANK = {"info": 0, "low": 1, "medium": 2, "high": 3, "critical": 4}

TOPOLOGY = {"nodes": [{"name": "order-service", "kind": "service", "description": "orders"},
                      {"name": "inventory-service", "kind": "service", "description": "stock"},
                      {"name": "payment-service", "kind": "service", "description": "payments"},
                      {"name": "shop-mysql", "kind": "database", "description": "MySQL"},
                      {"name": "shop-redis", "kind": "cache", "description": "Redis"}],
            "edges": [{"from": "order-service", "to": "inventory-service"},
                      {"from": "order-service", "to": "payment-service"},
                      {"from": "order-service", "to": "shop-mysql"},
                      {"from": "order-service", "to": "shop-redis"},
                      {"from": "inventory-service", "to": "shop-mysql"},
                      {"from": "payment-service", "to": "shop-mysql"}]}


def iso(t: datetime) -> str:
    return t.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


def alert(alert_id, rule_id, service, at, event_id, message, dependency=None):
    group_values = {"service": service, **({"fields.dependency": dependency} if dependency else {})}
    return {"alert_id": alert_id, "rule_id": rule_id, "rule_version": 1, "fingerprint": f"{rule_id}|{service}",
            "severity": "high", "group_key": service, "service": service, "group_values": group_values,
            "window_start": iso(at), "window_end": iso(at), "count": 5, "threshold": 3,
            "summary": f"{service} {rule_id}", "sample_event_ids": [event_id],
            "samples": [{"event_id": event_id, "timestamp": iso(at), "message": message}],
            "created_at": iso(at), "stored_at": iso(at)}


class ChatCore:
    def __init__(self) -> None:
        now = datetime.now(timezone.utc)
        self.incidents = {
            MYSQL_INCIDENT: {"incident_id": MYSQL_INCIDENT, "title": "order-service answered with DATABASE_UNAVAILABLE",
                             "status": "open", "severity": "high", "services": ["order-service", "payment-service"],
                             "subjects": ["order-service", "payment-service"], "shared_dependencies": ["shop-mysql"],
                             "alert_count": 2, "opened_at": iso(now - timedelta(minutes=6)),
                             "last_alert_at": iso(now - timedelta(minutes=2)), "version": 1},
            OLD_INCIDENT: {"incident_id": OLD_INCIDENT, "title": "payment-service failing calls",
                           "status": "open", "severity": "medium", "services": ["order-service"],
                           "subjects": ["payment-service"], "shared_dependencies": [], "alert_count": 1,
                           "opened_at": iso(now - timedelta(hours=3, minutes=5)),
                           "last_alert_at": iso(now - timedelta(hours=3)), "version": 1},
        }
        self.alerts = {
            MYSQL_INCIDENT: [
                alert(RECOVERY_ALERT, "shop-recovery-failing", "payment-service", now - timedelta(minutes=2),
                      RECOVERY_EVENT, "recovery step failed"),
                alert(DB_ALERT, "shop-database-unavailable", "order-service", now - timedelta(minutes=6), DB_EVENT,
                      "Cannot reach the database: Communications link failure"),
            ],
            OLD_INCIDENT: [alert(STRAY_ALERT, "shop-dependency-call-failures", "order-service",
                                 now - timedelta(hours=3), DB_EVENT, "timeout", dependency="payment-service")],
        }
        self.analyses = {MYSQL_INCIDENT: [{"analysis_id": "a1", "incident_id": MYSQL_INCIDENT, "status": "completed",
                                           "model": "deepseek-v4-pro", "summary": "MySQL 不可用", "uncertain": False,
                                           "hypotheses": [{"component": "shop-mysql", "cause": "down",
                                                           "confidence": "high", "evidence": []}]}]}
        # payment-service still taking charges, order-service failing to reach its database.
        self.log_lines = [
            {"event_id": LOG_EVENT, "timestamp": iso(now - timedelta(minutes=3)), "service": "payment-service",
             "host": "vps", "event_type": "payment_succeeded", "severity": "info", "message": "Payment succeeded"}]
        self.log_counts = {
            "payment-service": [{"key": "payment_succeeded", "count": 40, "severity": "info",
                                 "first_at": iso(now - timedelta(minutes=8)), "last_at": iso(now - timedelta(minutes=1)),
                                 "example": "Payment succeeded"}],
            "order-service": [{"key": "database_unavailable", "count": 5, "severity": "medium",
                               "first_at": iso(now - timedelta(minutes=6)), "last_at": iso(now - timedelta(minutes=2)),
                               "example": "Database unavailable: Communications link failure"}]}
        self.requests: list[httpx.Request] = []
        self.down = False
        # The writes that reached core-service, as (path, body), and how the next ones go wrong:
        # "lost": applied, but the answer never arrives; "dropped": neither; "lie": 200 without applying;
        # "race": someone else moves the incident just before; an int: that HTTP status.
        self.writes: list[tuple[str, dict | None]] = []
        self.write_fault: str | int | None = None

    def add_payment_incident(self, minutes_ago: int = 4) -> None:
        """A second fault, unrelated to the database: payment-service answering 503."""
        now = datetime.now(timezone.utc)
        self.incidents[PAYMENT_INCIDENT] = {
            "incident_id": PAYMENT_INCIDENT, "title": "payment-service answered with 503", "status": "open",
            "severity": "high", "services": ["order-service"], "subjects": ["payment-service"],
            "shared_dependencies": [], "alert_count": 1, "opened_at": iso(now - timedelta(minutes=minutes_ago + 1)),
            "last_alert_at": iso(now - timedelta(minutes=minutes_ago)), "version": 1}
        self.alerts[PAYMENT_INCIDENT] = [alert(PAYMENT_ALERT, "shop-dependency-call-failures", "order-service",
                                               now - timedelta(minutes=minutes_ago), PAYMENT_EVENT,
                                               "payment-service returned 503", dependency="payment-service")]

    def _write(self, request: httpx.Request) -> httpx.Response:
        path = request.url.path
        body = json.loads(request.content) if request.content else None
        incident_id = path.split("/")[4]
        incident = self.incidents.get(incident_id)
        if incident is None:
            return httpx.Response(404, json={"code": "INCIDENT_NOT_FOUND", "detail": "no such incident"})
        fault, self.write_fault = self.write_fault, None
        if fault == "dropped":
            raise httpx.ReadTimeout("timed out", request=request)
        if isinstance(fault, int):
            self.writes.append((path, body))
            return httpx.Response(fault, json={"code": "BOOM", "detail": "injected"})
        if fault == "race":
            incident["status"], incident["version"] = "investigating", incident["version"] + 1
        self.writes.append((path, body))
        if path.endswith("/analyses"):
            return httpx.Response(202, json={"analysis_id": "an-" + incident_id[-4:], "incident_id": incident_id,
                                             "status": "pending"})
        if body.get("expected_version") is not None and body["expected_version"] != incident["version"]:
            return httpx.Response(409, json={"code": "STALE_VERSION", "detail": "the incident changed"})
        if body["to_status"] not in MOVES[incident["status"]]:
            return httpx.Response(409, json={"code": "ILLEGAL_TRANSITION", "detail": "not allowed"})
        if fault != "lie":
            incident["status"], incident["version"] = body["to_status"], incident["version"] + 1
        if fault == "lost":
            raise httpx.ReadTimeout("timed out", request=request)
        return httpx.Response(200, json=copy.deepcopy(incident))

    def handle(self, request: httpx.Request) -> httpx.Response:
        self.requests.append(request)
        if self.down:
            return httpx.Response(503, json={"detail": "core is down"})
        if request.method == "POST":
            return self._write(request)
        path = request.url.path
        if path == "/api/v1/topology":
            return httpx.Response(200, json=TOPOLOGY)
        if path == "/api/v1/incidents":
            return httpx.Response(200, json={"items": sorted(copy.deepcopy(list(self.incidents.values())),
                                                             key=lambda i: i["opened_at"], reverse=True)})
        if path == "/api/v1/alerts":
            return httpx.Response(200, json={"items": [a for rows in self.alerts.values() for a in rows]})
        if path == "/api/v1/logs":
            service = request.url.params.get("service")
            floor = RANK[request.url.params.get("min_severity", "info")]
            return httpx.Response(200, json={"items": [line for line in self.log_lines
                                                       if service in (None, line["service"])
                                                       and RANK[line["severity"]] >= floor]})
        if path == "/api/v1/logs/counts":
            return httpx.Response(200, json=self.log_counts.get(request.url.params.get("service"), []))
        for incident_id, incident in self.incidents.items():
            base = f"/api/v1/incidents/{incident_id}"
            routes = {base: incident, base + "/alerts": self.alerts.get(incident_id, []),
                      base + "/timeline": [], base + "/analyses": self.analyses.get(incident_id, [])}
            if path in routes:
                return httpx.Response(200, json=copy.deepcopy(routes[path]))
        return httpx.Response(404, json={"detail": f"No {path}"})

    def api(self) -> CoreApi:
        return CoreApi("http://core", client=httpx.Client(base_url="http://core",
                                                          transport=httpx.MockTransport(self.handle)))

    def writer(self) -> CoreWriter:
        return CoreWriter("http://core", client=httpx.Client(base_url="http://core",
                                                             transport=httpx.MockTransport(self.handle)))


@dataclass
class Script:
    """One scripted main-model turn."""
    content: str = ""
    reasoning: str = ""
    tool_calls: list[tuple[str, str]] = field(default_factory=list)
    fail: Exception | None = None
    usage: dict = field(default_factory=dict)


class ScriptedStreamingModel:
    name = "scripted"

    def __init__(self, *turns: Script, quick: list[str | Exception] | None = None, quick_usage: dict | None = None):
        self.quick_usage = quick_usage
        self.turns = list(turns)
        self.quick_answers = list(quick or [])
        self.calls: list[dict] = []
        self.quick_calls: list[list[dict]] = []

    async def stream(self, messages, tools=None, *, on_reasoning=None, on_content=None, thinking=None) -> Turn:
        self.calls.append({"messages": copy.deepcopy(messages), "thinking": thinking,
                           "tools": [t["function"]["name"] for t in tools or []]})
        if not self.turns:
            raise AssertionError("the model was asked more often than scripted")
        script = self.turns.pop(0)
        if script.fail:
            raise script.fail
        for i in range(0, len(script.reasoning), 4):
            on_reasoning and on_reasoning(script.reasoning[i:i + 4])
        for i in range(0, len(script.content), 4):
            on_content and on_content(script.content[i:i + 4])
        return Turn(script.content, script.reasoning,
                    [ToolCall(f"call_{n}", name, args) for n, (name, args) in enumerate(script.tool_calls)], script.usage)

    async def quick(self, messages, *, json_output=False, timeout_s=None, on_content=None, on_usage=None) -> str:
        self.quick_calls.append(copy.deepcopy(messages))
        if not self.quick_answers:
            raise AssertionError("the quick model was asked more often than scripted")
        answer = self.quick_answers.pop(0)
        if isinstance(answer, Exception):
            raise answer
        if on_content:
            on_content(answer)
        if on_usage and self.quick_usage:
            on_usage(self.quick_usage)
        return answer


async def collect(events) -> list[tuple[str, dict]]:
    return [e async for e in events]


def of(events: list[tuple[str, dict]], name: str) -> list[dict]:
    return [d for e, d in events if e == name]


def answer(events: list[tuple[str, dict]]) -> str:
    """The answer as the browser shows it: interim text moves out of it."""
    text = ""
    for e, d in events:
        if e == "answer":
            text += d["delta"]
        elif e == "interim":
            text = ""
    return text
