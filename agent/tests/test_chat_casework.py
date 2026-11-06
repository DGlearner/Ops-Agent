"""Cases, runbooks, remediations and verifications from the agent's side (docs/agent-design.md §13.4)."""

import json
from datetime import datetime, timedelta, timezone

import pytest
from fastapi.testclient import TestClient

from case_support import CaseCore
from chat_support import DB_ALERT, MYSQL_INCIDENT, Script, ScriptedStreamingModel, answer, collect, iso, of
from ops_agent.chat import gaps as gap_rules
from ops_agent.chat import runbooks
from ops_agent.chat.actions import ActionConflict, ProposalError, digest
from ops_agent.chat.agent import ChatAgent
from ops_agent.chat.memory import ChatStore
from ops_agent.chat.server import create_app
from ops_agent.verify import Verifier

HANDLE = "下单失败了，帮我处理一下"


@pytest.fixture
def core():
    return CaseCore()


@pytest.fixture
def store(tmp_path):
    s = ChatStore(tmp_path / "chat.sqlite3")
    yield s
    s.close()


@pytest.fixture
def session(store):
    return store.create_session()["session_id"]


def agent(core, store, model=None, **kw):
    return ChatAgent(core.api(), store, model, writer=core.writer(), casework_on=True, **kw)


def kinds(events):
    return [e["kind"] for e in of(events, "step") if e["status"] != "running"]


async def test_without_a_model_a_ready_runbook_remediation_becomes_a_card_and_runs_once(core, store, session):
    chat = agent(core, store)
    events = await collect(chat.ask(session, HANDLE))

    assert "case" in kinds(events) and "runbook" in kinds(events)
    case = core.case_of(MYSQL_INCIDENT)
    assert case["status"] == "diagnosing"
    facts = [e for e in case["entries"] if e["kind"] == "fact"]
    assert len(facts) == 1 and facts[0]["source_type"] == "observation" and facts[0]["data"]["value"] == {
        "state": "exited"}
    [card] = of(events, "action")
    assert card["kind"] == "start_container" and card["params"] == {"name": "shop-mysql"}
    assert card["runbook"]["id"] == "mysql-unavailable" and card["preconditions"]
    assert card["payload_digest"] == digest({"case_id": case["case_id"], "incident_id": MYSQL_INCIDENT,
                                             "op": "start_container", "params": {"name": "shop-mysql"}})
    assert "等待你确认" in answer(events)
    assert core.lab_changes == []

    # Confirmed without the card's digest: not what was shown, nothing runs.
    with pytest.raises(ActionConflict) as e:
        await chat.desk.confirm(session, card["action_id"], card["version"], "sha256:00")
    assert e.value.code == "PAYLOAD_MISMATCH"
    done = await chat.desk.confirm(session, card["action_id"], card["version"], card["payload_digest"])
    assert done["status"] == "succeeded" and "不等于故障已恢复" in done["receipt"]["summary"]
    again = await chat.desk.confirm(session, card["action_id"], card["version"], card["payload_digest"])
    assert again["receipt"]["receipt_id"] == done["receipt"]["receipt_id"]
    assert core.lab_changes == ["start shop-mysql"]
    assert core.case_of(MYSQL_INCIDENT)["status"] == "verifying"

    [run] = store.runs(session)
    assert run["status"] == "paused_action" and run["case_id"] == case["case_id"]
    assert run["snapshot"]["runbook"] == {"id": "mysql-unavailable", "version": 1}
    assert run["snapshot_digest"].startswith("sha256:")


async def test_a_precondition_not_seen_keeps_the_remediation_from_being_proposed(core, store, session):
    core.containers["shop-mysql"] = "running"
    events = await collect(agent(core, store).ask(session, HANDLE))

    assert of(events, "action") == []
    assert "还不能提出处置" in answer(events) and "shop-mysql 容器没有在运行" in answer(events)
    [run] = store.runs(session)
    assert any(g["kind"] == "precondition_unmet" for g in run["snapshot"]["gaps"])


async def test_a_card_is_not_run_when_the_case_changed_after_it_was_proposed(core, store, session):
    chat = agent(core, store)
    [card] = of(await collect(chat.ask(session, HANDLE)), "action")
    core.case_of(MYSQL_INCIDENT)["state_version"] += 1  # a fact, constraint or decision changed meanwhile

    done = await chat.desk.confirm(session, card["action_id"], card["version"], card["payload_digest"])
    assert done["status"] == "blocked" and "处置单在提出操作后有变化" in done["receipt"]["summary"]
    assert core.lab_changes == []


async def test_a_lab_that_no_longer_looks_as_it_did_blocks_the_run(core, store, session):
    chat = agent(core, store)
    [card] = of(await collect(chat.ask(session, HANDLE)), "action")
    core.containers["shop-mysql"] = "running"  # someone started it meanwhile

    done = await chat.desk.confirm(session, card["action_id"], card["version"], card["payload_digest"])
    assert done["status"] == "blocked" and core.lab_changes == []
    assert core.case_of(MYSQL_INCIDENT)["status"] == "diagnosing"


def test_a_remediation_is_turned_down_while_a_gap_blocks_it(core, store, session):
    desk = agent(core, store).desk
    book = next(b for b in runbooks.load() if b.id == "mysql-unavailable")
    context = {"case": {"case_id": "c", "state_version": 1, "status": "diagnosing",
                        "incident_ids": [MYSQL_INCIDENT]},
               "match": runbooks.Match(book, 3), "options": [{"op": "start_container", "ready": True,
                                                              "params": {"name": "shop-mysql"}, "met": ["x"],
                                                              "unmet": [], "effect": "", "blast_radius": "",
                                                              "rollback": ""}],
               "gaps": [gap_rules.Gap("target_ambiguous", "不确定是哪个", (gap_rules.REMEDIATE,))], "forbidden": {}}
    with pytest.raises(ProposalError, match="target_ambiguous"):
        desk._remedial(desk_kind("start_container"), {"name": "shop-mysql"}, MYSQL_INCIDENT, context)
    context["gaps"] = []
    context["forbidden"] = {"start_container": "K1：先别动数据库"}
    with pytest.raises(ProposalError, match="限制"):
        desk._remedial(desk_kind("start_container"), {"name": "shop-mysql"}, MYSQL_INCIDENT, context)
    context["forbidden"] = {}
    with pytest.raises(ProposalError, match="参数要用 Runbook"):
        desk._remedial(desk_kind("start_container"), {"name": "shop-redis"}, MYSQL_INCIDENT, context)
    assert desk._remedial(desk_kind("start_container"), {"name": "shop-mysql"}, MYSQL_INCIDENT, context)["remedial"]


def desk_kind(name):
    from ops_agent.chat.actions import KINDS
    return KINDS[name]


async def test_the_model_may_note_hypotheses_and_quoted_user_words_but_not_facts(core, store, session):
    def update(args, _):
        return ("update_case", json.dumps(args, ensure_ascii=False))

    model = ScriptedStreamingModel(
        Script(tool_calls=[update({"kind": "fact", "text": "MySQL 停了"}, "c1"),
                           update({"kind": "statement", "text": "用户重启过", "quote": "我已经重启过 MySQL 了"}, "c2"),
                           update({"kind": "statement", "text": "用户说刚才重启过", "quote": "刚才我重启过数据库"}, "c3"),
                           update({"kind": "constraint", "text": "大促期间不能重启", "quote": "大促期间别重启",
                                   "forbids": ["start_container"]}, "c4"),
                           update({"kind": "hypothesis", "text": "MySQL 停机", "status": "supported",
                                   "evidence": ["A1", "P1"]}, "c5")]),
        Script(content="已记下。"), quick=['{"route": "ops_diagnosis", "reason": "x", "services": ["order-service"]}'])
    chat = agent(core, store, model, max_tool_calls=6)
    events = await collect(chat.ask(session, "下单失败，刚才我重启过数据库，大促期间别重启，帮我看看"))

    tools = [e for e in of(events, "step") if e["kind"] == "tool" and e["status"] != "running"]
    assert [t["status"] for t in tools] == ["error", "error", "done", "done", "done"]
    entries = core.case_of(MYSQL_INCIDENT)["entries"]
    by_kind = {e["kind"]: e for e in entries}
    assert by_kind["statement"]["source_type"] == "user" and by_kind["statement"]["data"]["quote"] == "刚才我重启过数据库"
    assert by_kind["constraint"]["data"]["forbids"] == ["start_container"]
    assert by_kind["hypothesis"]["source_type"] == "model" and by_kind["hypothesis"]["status"] == "supported"
    assert not [e for e in entries if e["kind"] == "fact" and e["source_type"] != "observation"]


async def test_kill_transaction_takes_its_parameters_from_what_the_probe_saw(core, store, session):
    core.alerts[MYSQL_INCIDENT][1]["samples"][0]["message"] = "Lock wait timeout exceeded; try restarting transaction"
    core.transactions = [{"trx_id": "4211", "thread_id": 77, "age_seconds": 25, "state": "RUNNING", "blocking": 3,
                          "user": "inventory_svc"},
                         {"trx_id": "4212", "thread_id": 78, "age_seconds": 40, "state": "RUNNING", "blocking": 0,
                          "user": "order_svc"}]
    chat = agent(core, store)
    events = await collect(chat.ask(session, HANDLE))
    [card] = of(events, "action")
    assert card["kind"] == "kill_transaction"
    # The one blocking others, not merely the oldest.
    assert card["params"] == {"trx_id": "4211", "thread_id": 77}
    done = await chat.desk.confirm(session, card["action_id"], card["version"], card["payload_digest"])
    assert done["status"] == "succeeded" and core.lab_changes == ["kill 77"]


async def test_without_performance_schema_the_holder_is_the_oldest_not_waiting_and_the_count_stays_unknown(core, store,
                                                                                                            session):
    # What the lab's MySQL really reports: no "blocking", and the longest-lived may be the one held up.
    core.alerts[MYSQL_INCIDENT][1]["samples"][0]["message"] = "Lock wait timeout exceeded; try restarting transaction"
    core.transactions = [{"trx_id": "4210", "thread_id": 76, "age_seconds": 60, "state": "LOCK WAIT", "user": "svc"},
                         {"trx_id": "4211", "thread_id": 77, "age_seconds": 25, "state": "RUNNING", "user": "svc"}]
    events = await collect(agent(core, store).ask(session, HANDLE))
    [card] = of(events, "action")
    assert card["params"] == {"trx_id": "4211", "thread_id": 77}
    probe = next(e for e in of(events, "step") if e["kind"] == "runbook" and e["status"] == "done")
    assert "阻塞数未知" in probe["result"] and "阻塞 0 个" not in probe["result"]


async def test_nothing_to_end_when_every_long_transaction_is_itself_waiting(core, store, session):
    core.alerts[MYSQL_INCIDENT][1]["samples"][0]["message"] = "Lock wait timeout exceeded; try restarting transaction"
    core.transactions = [{"trx_id": "4210", "thread_id": 76, "age_seconds": 60, "state": "LOCK WAIT", "user": "svc"}]
    assert of(await collect(agent(core, store).ask(session, HANDLE)), "action") == []


async def test_a_transaction_waiting_on_a_lock_turns_the_inventory_stopgap_into_the_lock_runbook(core, store, session):
    # Early in a row-lock fault: order-service's timeouts are in the logs, "Lock wait timeout" not yet.
    for a in core.alerts[MYSQL_INCIDENT]:
        a.update(summary="10 failed calls from order-service to inventory-service within 1m",
                 rule_id="shop-dependency-call-failures")
        for sample in a.get("samples") or []:
            sample["message"] = "Inventory reservation failed: no response within the read timeout"
    chat = agent(core, store)

    # A long transaction, but nobody waits on it: inventory-service is just failing, so the stopgap is on offer.
    core.transactions = [{"trx_id": "4100", "thread_id": 70, "age_seconds": 30, "state": "RUNNING", "user": "report"}]
    events = await collect(chat.ask(session, HANDLE))
    [stopgap] = of(events, "action")
    assert stopgap["kind"] == "set_switch" and stopgap["runbook"]["id"] == "inventory-degraded"
    await chat.desk.decline(session, stopgap["action_id"], stopgap["version"])

    # Someone is waiting on a lock, its holder still young: not the stopgap that fails every order, and not ending it
    # yet either. The shape the lab's MySQL gives: states, no count of who blocks whom.
    core.transactions = [{"trx_id": "4211", "thread_id": 77, "age_seconds": 4, "state": "RUNNING", "user": "svc"},
                         {"trx_id": "4212", "thread_id": 78, "age_seconds": 2, "state": "LOCK WAIT", "user": "svc"}]
    events = await collect(chat.ask(session, HANDLE))
    assert of(events, "action") == [] and "行锁等待" in answer(events) and "还不能提出处置" in answer(events)
    titles = [e["title"] for e in of(events, "step") if e["kind"] == "runbook" and e["status"] == "done"]
    assert titles == ["按 Runbook「库存服务出错或变慢」检查", "按 Runbook「行锁等待」检查"]

    # Long enough now: end that one.
    core.transactions[0]["age_seconds"] = 15
    [card] = of(await collect(chat.ask(session, HANDLE)), "action")
    assert card["kind"] == "kill_transaction" and card["params"] == {"trx_id": "4211", "thread_id": 77}
    assert card["runbook"]["id"] == "row-lock-wait"


async def test_a_probe_that_failed_says_nothing_about_handing_over():
    book = next(b for b in runbooks.load() if b.id == "inventory-degraded")
    failed = runbooks.Observation("lock_waits", "t", False, None, "查不到")
    empty = runbooks.Observation("lock_waits", "t", True, [], "没有")
    assert runbooks.deferred(book, [failed], runbooks.load()) is None
    assert runbooks.deferred(book, [empty], runbooks.load()) is None
    assert runbooks.deferred(book, [], runbooks.load()) is None


async def test_the_page_tells_which_incident_this_is(core, store, session):
    events = await collect(agent(core, store).ask(session, "这个怎么回事？", page={"incident_id": MYSQL_INCIDENT}))
    locate = next(e for e in of(events, "step") if e["kind"] == "locate" and e["status"] == "done")
    assert "当前页面" in locate["result"]


def test_a_repeated_request_gets_the_answer_it_already_had(core, store, session):
    client = TestClient(create_app(agent(core, store), store))
    body = {"content": "刚才下单为什么失败？", "request_id": "req-00000001"}
    first = client.post(f"/api/v1/chat/sessions/{session}/messages", json=body)
    second = client.post(f"/api/v1/chat/sessions/{session}/messages", json=body)
    assert first.status_code == second.status_code == 200
    assert '"replayed": true' in second.text
    assert len(store.runs(session)) == 1
    assert len([m for m in store.messages(session) if m["role"] == "assistant"]) == 1



def test_the_verifier_judges_from_alerts_and_logs_and_needs_data_to_pass(core):
    now = datetime.now(timezone.utc)
    v = {"verification_id": "v", "case_id": "c", "token": 1, "window_from": iso(now - timedelta(minutes=2)),
         "not_before": iso(now), "criteria": {"goal": "recover", "checks": [
             {"id": "orders_paid", "kind": "events_at_least", "service": "order-service", "event_type": "order_paid",
              "min": 1, "title": "有成功订单"}]}}
    verifier = Verifier(core.api(), core.writer(), "w")
    core.log_counts["order-service"] = []
    assert verifier.judge(v)[0] == "inconclusive"  # no logs at all: says nothing
    core.log_counts["order-service"] = [{"key": "order_cancelled", "count": 9}]
    assert verifier.judge(v)[0] == "not_recovered"
    core.log_counts["order-service"] = [{"key": "order_paid", "count": 9}]
    assert verifier.judge(v)[0] == "recovered"
    v["criteria"]["checks"].append({"id": "no_new_alerts", "kind": "no_alerts", "services": ["order-service"],
                                    "title": "无新告警"})
    next(a for rows in core.alerts.values() for a in rows)["window_start"] = iso(now - timedelta(seconds=30))
    outcome, result = verifier.judge(v)
    assert outcome == "not_recovered"  # an alert about the time after the remediation
    assert result["checks"][1]["verdict"] == "not_met"


async def test_a_verification_is_judged_once_with_its_token_and_does_not_resolve_the_case(core, store, session):
    chat = agent(core, store)
    [card] = of(await collect(chat.ask(session, HANDLE)), "action")
    await chat.desk.confirm(session, card["action_id"], card["version"], card["payload_digest"])
    core.alerts = {}
    core.log_counts["order-service"] = [{"key": "order_paid", "count": 12}]
    verifier = Verifier(core.api(), core.writer(), "w")

    done = verifier.once()
    assert done["outcome"] == "recovered" and done["accepted"]
    assert core.case_of(MYSQL_INCIDENT)["status"] == "monitoring"
    assert verifier.once() is None
    late = core.writer().verification_result(done["verification"]["verification_id"], 1, "not_recovered", {})
    assert late.code == "STALE_TOKEN"


def test_runbooks_match_on_signals_not_on_pointing_alone():
    books = runbooks.load()
    assert {b.id for b in books} == {"mysql-unavailable", "row-lock-wait", "payment-degraded", "inventory-degraded"}
    assert runbooks.match(books, ["shop-mysql"], ["nothing relevant"]) is None
    assert runbooks.match(books, ["shop-mysql"], ["Communications link failure"]).runbook.id == "mysql-unavailable"
    assert runbooks.match(books, ["inventory-service"], ["Lock wait timeout exceeded"]).runbook.id == "row-lock-wait"
    assert runbooks.match(books, ["payment-service"],
                          ["Charge request did not reach payment: HTTP 503"]).runbook.id == "payment-degraded"


def test_the_digest_is_the_one_core_service_computes():
    # The same payload and value as core-service's DigestTest.
    payload = {"case_id": "c-1", "incident_id": "i-1", "op": "set_switch",
               "params": {"name": "payment.fast-fail", "on": True, "minutes": 5}}
    assert json.dumps(payload, sort_keys=True, separators=(",", ":")) == (
        '{"case_id":"c-1","incident_id":"i-1","op":"set_switch","params":{"minutes":5,"name":"payment.fast-fail",'
        '"on":true}}')
    assert digest(payload) == DIGEST


DIGEST = "sha256:" + __import__("hashlib").sha256(
    b'{"case_id":"c-1","incident_id":"i-1","op":"set_switch","params":{"minutes":5,"name":"payment.fast-fail",'
    b'"on":true}}').hexdigest()


def test_an_alert_about_the_minute_before_the_remediation_does_not_count_against_it(core):
    now = datetime.now(timezone.utc)
    start = now - timedelta(minutes=2)
    v = {"verification_id": "v", "case_id": "c", "token": 1, "window_from": iso(start), "not_before": iso(now),
         "criteria": {"goal": "recover", "checks": [{"id": "no_new_alerts", "kind": "no_alerts",
                                                     "services": ["order-service"], "title": "无新告警"}]}}
    alerts = [a for rows in core.alerts.values() for a in rows]
    for a in alerts:
        a["window_start"] = iso(start - timedelta(seconds=60))  # raised in the window, about the minute before
    verifier = Verifier(core.api(), core.writer(), "w")
    assert verifier.judge(v)[0] == "recovered"
    alerts[0]["window_start"] = iso(start + timedelta(seconds=30))
    assert verifier.judge(v)[0] == "not_recovered"


async def test_a_component_of_the_incident_on_the_page_still_means_that_incident(core, store, session):
    from chat_support import PAYMENT_INCIDENT
    core.add_payment_incident()
    events = await collect(agent(core, store).ask(session, HANDLE, page={"incident_id": MYSQL_INCIDENT}))
    locate = next(e for e in of(events, "step") if e["kind"] == "locate" and e["status"] == "done")
    assert "当前页面" in locate["result"] and core.case_of(MYSQL_INCIDENT) is not None
    # Another service than the page's incident has: the question is about something else.
    other = store.create_session()["session_id"]
    events = await collect(agent(core, store).ask(other, "库存服务怎么了", page={"incident_id": PAYMENT_INCIDENT}))
    locate = next(e for e in of(events, "step") if e["kind"] == "locate" and e["status"] == "done")
    assert "当前页面" not in locate["result"]
