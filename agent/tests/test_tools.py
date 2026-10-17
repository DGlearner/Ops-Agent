import json

from conftest import A1, A2, A3, E1, INCIDENT, OTHER_INCIDENT_ID
from ops_agent.contracts import Evidence
from ops_agent.tools import MAX_MESSAGE_CHARS, Toolbox


def run(toolbox, name, **args):
    return json.loads(toolbox.call(name, json.dumps(args)))


def test_the_incident_alerts_come_in_time_order_with_short_samples(core):
    alerts = run(Toolbox(core, INCIDENT), "get_incident_alerts")["alerts"]

    assert [a["alert_id"] for a in alerts] == [A1, A2]
    assert alerts[0]["group_values"] == {"service": "order-service"}
    assert alerts[0]["samples"][0]["event_id"] == E1
    assert len(alerts[0]["samples"][0]["message"]) == MAX_MESSAGE_CHARS
    assert "fingerprint" not in alerts[0]


def test_alerts_around_the_incident_are_marked_whether_they_belong_to_it(core, fake_core):
    found = run(Toolbox(core, INCIDENT), "search_alerts", minutes_before=10, minutes_after=5, service="order-service")

    assert {a["alert_id"]: a["in_this_incident"] for a in found["alerts"]} == {A1: True, A2: True, A3: False}
    # The incident spans 10:00:00-10:00:20; A3 came at 10:05:00.
    assert {a["alert_id"]: (a["timing"], a.get("seconds_away")) for a in found["alerts"]} == {
        A1: ("during", None), A2: ("during", None), A3: ("after", 280)}
    query = fake_core.query("/api/v1/alerts")
    assert query["from"] == "2026-10-17T09:50:00Z" and query["to"] == "2026-10-17T10:05:20Z"
    assert query["service"] == "order-service" and "rule_id" not in query


def test_nearby_incidents_mark_this_one(core, fake_core):
    nearby = run(Toolbox(core, INCIDENT), "list_nearby_incidents")

    assert [(i["incident_id"], i["this"]) for i in nearby] == [(INCIDENT["incident_id"], True),
                                                              (OTHER_INCIDENT_ID, False)]
    assert fake_core.query("/api/v1/incidents")["from"] == "2026-10-17T09:00:00Z"
    assert [i["timing"] for i in nearby] == ["during", "during"]


def test_alerts_are_searched_ten_minutes_around_the_incident_by_default(core, fake_core):
    run(Toolbox(core, INCIDENT), "search_alerts")
    query = fake_core.query("/api/v1/alerts")
    assert (query["from"], query["to"]) == ("2026-10-17T09:50:00Z", "2026-10-17T10:10:20Z")


def test_the_timeline_keeps_what_happened_and_when(core):
    assert run(Toolbox(core, INCIDENT), "get_incident_timeline") == [
        {"type": "OPENED", "at": "2026-10-17T10:00:01Z", "payload": {"subject": "order-service"}},
        {"type": "STATUS_CHANGED", "at": "2026-10-17T10:03:00Z", "to_status": "acknowledged"}]


def test_the_topology_lists_what_each_service_depends_on(core):
    services = {s["name"]: s for s in run(Toolbox(core, INCIDENT), "get_topology")["services"]}

    assert services["order-service"]["depends_on"] == ["inventory-service", "payment-service", "shop-mysql"]
    assert services["shop-mysql"] == {"name": "shop-mysql", "kind": "database", "description": "MySQL",
                                      "depends_on": []}


def test_mistakes_and_failures_are_results_the_model_can_read(core, fake_core):
    toolbox = Toolbox(core, INCIDENT)
    assert "没有工具" in toolbox.call("restart_mysql", "{}")
    assert "不是 JSON" in toolbox.call("search_alerts", "{not json")
    assert "参数不对" in toolbox.call("search_alerts", json.dumps({"minutes_before": 1000}))
    fake_core.fail.add("/api/v1/topology")
    assert "查询失败" in toolbox.call("get_topology", "")
    fake_core.incident.pop("opened_at")
    assert "工具出错" in toolbox.call("list_nearby_incidents", "")


def test_evidence_the_incident_does_not_have_is_found(core):
    toolbox = Toolbox(core, INCIDENT)
    good = [Evidence(kind="alert", id=A1.upper()), Evidence(kind="event", id=E1),
            Evidence(kind="topology", id="shop-mysql"), Evidence(kind="incident", id=INCIDENT["incident_id"])]
    bad = [Evidence(kind="alert", id=A3), Evidence(kind="topology", id="redis"),
           Evidence(kind="incident", id=OTHER_INCIDENT_ID)]

    assert toolbox.unknown_evidence(good + bad) == bad
