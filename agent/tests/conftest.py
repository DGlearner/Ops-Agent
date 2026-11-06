"""A fake core-service (httpx's mock transport, so the real client is exercised) with one MySQL outage incident,
and a scripted model."""

import copy
import json
from urllib.parse import parse_qs

import httpx
import pytest

from ops_agent.config import Settings
from ops_agent.contracts import Schemas
from ops_agent.core_api import CoreApi
from ops_agent.llm import Reply, ToolCall

INCIDENT_ID = "0199f000-0000-7000-8000-000000000001"
OTHER_INCIDENT_ID = "0199f000-0000-7000-8000-000000000002"
A1 = "0199f000-0000-7000-8000-0000000000a1"
A2 = "0199f000-0000-7000-8000-0000000000a2"
A3 = "0199f000-0000-7000-8000-0000000000a3"
E1 = "0199f000-0000-7000-8000-0000000000e1"
E2 = "0199f000-0000-7000-8000-0000000000e2"


def alert(alert_id, rule_id, service, created_at, event_id, message, dependency=None):
    group_values = {"service": service, **({"fields.dependency": dependency} if dependency else {})}
    return {"alert_id": alert_id, "rule_id": rule_id, "rule_version": 1, "fingerprint": f"{rule_id}|{service}",
            "severity": "high", "group_key": service, "service": service, "host": "vps",
            "group_values": group_values, "window_start": created_at, "window_end": created_at, "count": 5,
            "threshold": 3, "summary": f"{rule_id} on {service}", "sample_event_ids": [event_id],
            "samples": [{"event_id": event_id, "timestamp": created_at, "message": message, "trace_id": "t1"}],
            "created_at": created_at, "stored_at": created_at}


INCIDENT = {"incident_id": INCIDENT_ID, "title": "order-service answered 5 requests with DATABASE_UNAVAILABLE",
            "status": "open", "severity": "high", "services": ["order-service", "payment-service"],
            "subjects": ["order-service", "payment-service"], "shared_dependencies": ["shop-mysql"],
            "alert_count": 2, "opened_at": "2026-10-17T10:00:00Z", "last_alert_at": "2026-10-17T10:00:20Z",
            "version": 2}
ALERTS = [
    alert(A2, "shop-recovery-failing", "payment-service", "2026-10-17T10:00:20Z", E2, "recovery step failed"),
    alert(A1, "shop-database-unavailable", "order-service", "2026-10-17T10:00:00Z", E1,
          "Cannot reach the database: Communications link failure " + "x" * 400),
]
OTHER_ALERT = alert(A3, "shop-dependency-call-failures", "order-service", "2026-10-17T10:05:00Z", E2, "timeout",
                    dependency="payment-service")
TOPOLOGY = {"nodes": [{"name": "order-service", "kind": "service", "description": "orders"},
                      {"name": "inventory-service", "kind": "service", "description": "stock"},
                      {"name": "payment-service", "kind": "service", "description": "payments"},
                      {"name": "shop-mysql", "kind": "database", "description": "MySQL"}],
            "edges": [{"from": "order-service", "to": "inventory-service"},
                      {"from": "order-service", "to": "payment-service"},
                      {"from": "order-service", "to": "shop-mysql"},
                      {"from": "inventory-service", "to": "shop-mysql"},
                      {"from": "payment-service", "to": "shop-mysql"}]}
# As core-service returns them (TimelineEntry).
TIMELINE = [{"event_id": "0199f000-0000-7000-8000-0000000000b1", "type": "OPENED", "actor_type": "SYSTEM",
             "payload": {"subject": "order-service"}, "at": "2026-10-17T10:00:01Z"},
            {"event_id": "0199f000-0000-7000-8000-0000000000b2", "type": "STATUS_CHANGED", "actor_type": "USER",
             "from_status": "open", "to_status": "acknowledged", "payload": {}, "at": "2026-10-17T10:03:00Z"}]


LOG_EVENT = "0199f000-0000-7000-8000-0000000000f1"
LOG_LINE = {"event_id": LOG_EVENT, "timestamp": "2026-10-17T10:00:10Z", "received_at": "2026-10-17T10:00:11Z",
            "service": "payment-service", "host": "vps", "source_type": "app_service",
            "event_type": "payment_succeeded", "severity": "info", "message": "Payment succeeded", "fields": {}}
LOG_COUNT = {"key": "payment_succeeded", "count": 42, "severity": "info", "first_at": "2026-10-17T09:55:00Z",
             "last_at": "2026-10-17T10:05:00Z", "example": "Payment succeeded"}


class FakeCore:
    def __init__(self, incident=None, alerts=None):
        self.incident = copy.deepcopy(incident or INCIDENT)
        self.alerts = copy.deepcopy(alerts if alerts is not None else ALERTS)
        self.requests: list[httpx.Request] = []
        self.fail: set[str] = set()

    def handle(self, request: httpx.Request) -> httpx.Response:
        self.requests.append(request)
        path = request.url.path
        if path in self.fail:
            return httpx.Response(503, json={"detail": "database unavailable"})
        incident = f"/api/v1/incidents/{self.incident['incident_id']}"
        routes = {
            incident: self.incident,
            incident + "/alerts": self.alerts,
            incident + "/timeline": TIMELINE,
            "/api/v1/topology": TOPOLOGY,
            "/api/v1/logs": {"items": [LOG_LINE]},
            "/api/v1/logs/counts": [LOG_COUNT],
            "/api/v1/alerts": {"items": [*self.alerts, OTHER_ALERT]},
            "/api/v1/incidents": {"items": [self.incident, {**INCIDENT, "incident_id": OTHER_INCIDENT_ID,
                                                            "subjects": ["payment-service"]}]},
        }
        if path not in routes:
            return httpx.Response(404, json={"detail": f"No {path}"})
        return httpx.Response(200, json=routes[path])

    def query(self, path: str) -> dict:
        request = next(r for r in self.requests if r.url.path == path)
        return {k: v[0] for k, v in parse_qs(request.url.query.decode()).items()}


@pytest.fixture
def fake_core() -> FakeCore:
    return FakeCore()


@pytest.fixture
def core(fake_core) -> CoreApi:
    return CoreApi("http://core", client=httpx.Client(base_url="http://core",
                                                      transport=httpx.MockTransport(fake_core.handle)))


@pytest.fixture
def schemas() -> Schemas:
    return Schemas(Settings().contracts_dir)


class ScriptedModel:
    """Answers with the given replies in order and records what it was sent."""
    name = "scripted"

    def __init__(self, *replies: Reply):
        self.replies = list(replies)
        self.calls: list[dict] = []

    def complete(self, messages, tools, force_tool=None):
        self.calls.append({"messages": copy.deepcopy(messages), "tools": [t["function"]["name"] for t in tools],
                           "force_tool": force_tool})
        if not self.replies:
            raise AssertionError("the model was asked more often than scripted")
        return self.replies.pop(0)


_ids = iter(range(1_000_000))


def tool(name: str, **arguments) -> Reply:
    return Reply(None, [ToolCall(f"call_{next(_ids)}", name, json.dumps(arguments))])


def submit(**arguments) -> Reply:
    return tool("submit_analysis", **arguments)


def mysql_answer(*evidence, confidence="high") -> Reply:
    return submit(summary="MySQL 不可用，订单和支付都失败。", uncertain=False,
                  hypotheses=[{"component": "shop-mysql", "cause": "MySQL 宕机", "confidence": confidence,
                               "evidence": list(evidence)}],
                  next_steps=["检查 shop-mysql 容器"])
