"""The outcome evaluation's own judgement: what counts as this fault's detection, and when a round is not clean. These
are the mistakes a live run made: an open incident swallowed 7 rounds' faults and the report said "not detected"; the
last fault's aftermath was scored as the next one's detection, at a negative time.
"""

import json
from datetime import datetime, timedelta, timezone

from ops_agent.chat import outcome_eval

T0 = datetime(2031, 3, 5, 10, 0, tzinfo=timezone.utc)


def iso(seconds: float) -> str:
    return (T0 + timedelta(seconds=seconds)).isoformat().replace("+00:00", "Z")


def incident(id_: str, opened: float, last: float | None = None, status: str = "open") -> dict:
    return {"incident_id": id_, "status": status, "opened_at": iso(opened), "last_alert_at": iso(last or opened)}


class Reply:
    def __init__(self, body: dict):
        self._body = body

    def json(self) -> dict:
        return self._body


class Core:
    def __init__(self, incidents=(), alerts=()):
        self.incidents = list(incidents)
        self.alerts = list(alerts)
        self.resolved: list[str] = []

    def get(self, path: str, params: dict | None = None) -> Reply:
        if path == "/api/v1/alerts":
            return Reply({"items": self.alerts})
        params = params or {}
        items = [i for i in self.incidents if params.get("status") in (None, i["status"])]
        return Reply({"items": items})

    def post(self, path: str, json: dict) -> Reply:  # noqa: A002 - httpx's name
        id_ = path.split("/")[4]
        self.resolved.append(id_)
        next(i for i in self.incidents if i["incident_id"] == id_)["status"] = "resolved"
        return Reply({})


def no_sleep(monkeypatch):
    monkeypatch.setattr(outcome_eval.time, "sleep", lambda s: None)


def test_an_incident_opened_before_the_injection_is_not_its_detection(monkeypatch):
    no_sleep(monkeypatch)
    core = Core([incident("old", -11)])
    assert outcome_eval._await_incident(core, T0, timeout_s=0.01) is None


def test_the_first_incident_after_the_injection_is_the_detection():
    core = Core([incident("later", 30), incident("first", 5), incident("closed", 1, status="resolved")])
    assert outcome_eval._await_incident(core, T0, timeout_s=1)["incident_id"] == "first"


def test_a_fault_that_went_onto_an_older_open_incident_is_named():
    core = Core([incident("swallowed-it", -3600, last=40), incident("quiet", -3600, last=-600)])
    assert outcome_eval._absorbing(core, T0) == ["swallowed-it"]


def test_settling_closes_what_is_open_and_waits_for_the_alerts_to_stop(monkeypatch):
    no_sleep(monkeypatch)
    core = Core([incident("leftover", -100)], alerts=[{"alert_id": "a"}])
    waits = iter([[{"alert_id": "a"}], []])
    real_get = core.get

    def get(path, params=None):
        if path == "/api/v1/alerts":
            return Reply({"items": next(waits)})
        return real_get(path, params)

    core.get = get
    settled = outcome_eval._settle(core)
    assert settled["quiet"] is True and settled["closed"] == ["leftover"] and core.resolved == ["leftover"]


def test_settling_says_so_when_the_alerts_never_stop(monkeypatch):
    no_sleep(monkeypatch)
    core = Core(alerts=[{"alert_id": "a"}])
    assert outcome_eval._settle(core, timeout_s=0)["quiet"] is False


def result(name: str, **fields) -> dict:
    return {"scenario": name, "target": "t", "detected": True, "confirmed": [], **fields}


def test_the_rounds_are_counted_per_scenario_and_a_stopgap_is_not_a_recovery():
    kill = [{"kind": "kill_transaction", "params": {}, "receipt": "succeeded"}]
    switch = [{"kind": "set_switch", "params": {}, "receipt": "succeeded"}]
    results = [result("hot-row-lock", confirmed=kill, verification="recovered", goal="recover", verified_after_s=150),
               result("hot-row-lock", confirmed=switch, verification="recovered", goal="contain", verified_after_s=130),
               result("hot-row-lock", detected=False, absorbed_by=["x"], settled={"quiet": False})]
    by = outcome_eval._by_scenario(results)["hot-row-lock"]
    assert by["rounds"] == 3 and by["detected"] == 2 and by["absorbed"] == 1
    assert by["cards"] == {"kill_transaction": 1, "set_switch": 1}
    assert by["verifications"] == {"recovered": 1, "contained": 1}
    summary = outcome_eval._summary(results)
    assert summary["recovered"] == 1 and summary["contained"] == 1
    assert summary["absorbed"] == 1 and summary["not_quiet_at_start"] == 1


def test_a_stopped_run_keeps_the_scenarios_it_finished(monkeypatch, tmp_path):
    out = tmp_path / "report.json"

    class Client:
        def __init__(self, **_):
            pass

        def get(self, path):
            return Reply({"model": None})

        def post(self, path, json=None):  # noqa: A002 - httpx's name
            return Reply({})

        def delete(self, path):
            return Reply({})

    seen = []

    def scenario(name, *_):
        if len(seen) == 2:
            raise KeyboardInterrupt
        seen.append(name)
        return result(name)

    no_sleep(monkeypatch)
    monkeypatch.setattr(outcome_eval.httpx, "Client", Client)
    monkeypatch.setattr(outcome_eval, "_switches_off", lambda core: [])
    monkeypatch.setattr(outcome_eval, "_settle", lambda core: {"quiet": True})
    monkeypatch.setattr(outcome_eval, "_scenario", scenario)
    try:
        outcome_eval.main(["a"], "c", "l", "o", out, rounds=3)
    except KeyboardInterrupt:
        pass
    report = json.loads(out.read_text(encoding="utf-8"))
    assert [r["round"] for r in report["scenarios"]] == [1, 2]
