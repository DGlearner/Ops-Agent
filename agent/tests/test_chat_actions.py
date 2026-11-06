"""Action cards: the model proposes, the user confirms, the system runs it once and says what really happened."""

import asyncio
import json
from datetime import datetime, timedelta, timezone

import pytest

from chat_support import (DB_ALERT, DB_EVENT, MYSQL_INCIDENT, PAYMENT_ALERT, PAYMENT_INCIDENT, ChatCore, Script,
                          ScriptedStreamingModel, answer, collect, of)
from ops_agent.chat.actions import ActionConflict, ActionDesk, ProposalError
from ops_agent.chat.agent import ChatAgent
from ops_agent.chat.evidence import RefTable
from ops_agent.chat.memory import ChatStore


@pytest.fixture
def core():
    c = ChatCore()
    c.add_payment_incident()
    return c


@pytest.fixture
def store(tmp_path):
    s = ChatStore(tmp_path / "chat.sqlite3")
    yield s
    s.close()


@pytest.fixture
def session(store):
    return store.create_session()["session_id"]


def refs_for(store, session) -> RefTable:
    """The refs as an answer about both incidents leaves them: I1 MySQL with A1 and E1, I2 payment with A2."""
    refs = RefTable()
    refs.ref("incident", MYSQL_INCIDENT, "order-service answered with DATABASE_UNAVAILABLE")
    refs.ref("alert", DB_ALERT, "order-service shop-database-unavailable", MYSQL_INCIDENT)
    refs.ref("event", DB_EVENT, "Communications link failure", MYSQL_INCIDENT)
    refs.ref("incident", PAYMENT_INCIDENT, "payment-service answered with 503")
    refs.ref("alert", PAYMENT_ALERT, "payment 503", PAYMENT_INCIDENT)
    store.update_session(session, refs=refs.as_json())
    return refs


def desk(store, core, **kw) -> ActionDesk:
    return ActionDesk(store, core.api(), core.writer(), **kw)


ACK = {"kind": "transition_incident", "incident": "I1", "params": {"to_status": "acknowledged", "note": "值班已接手"},
       "evidence": ["A1", "E1"], "reason": "MySQL 停机，已在处理"}


async def test_a_confirmed_action_runs_once_and_its_receipt_says_what_core_service_did(store, core, session):
    d = desk(store, core)
    card = await d.propose(session, "m1", refs_for(store, session), ACK)

    assert (card["status"], card["version"], card["title"]) == ("proposed", 1, "把 I1 改为「已确认」")
    assert card["target"] == {"ref": "I1", "incident_id": MYSQL_INCIDENT,
                              "title": core.incidents[MYSQL_INCIDENT]["title"], "status": "open", "version": 1}
    assert [e["ref"] for e in card["evidence"]] == ["A1", "E1"]
    assert core.writes == [], "proposing changes nothing"

    done = await d.confirm(session, card["action_id"], 1)

    receipt = done["receipt"]
    assert (done["status"], done["version"]) == ("succeeded", 3)
    assert receipt["status"] == "succeeded" and receipt["executed"] is True
    assert receipt["response"]["status"] == 200 and receipt["observed"] == {"status": "acknowledged", "version": 2}
    [(path, body)] = core.writes
    assert path == f"/api/v1/incidents/{MYSQL_INCIDENT}/transitions"
    assert body == {"to_status": "acknowledged", "expected_version": 1, "action_id": card["action_id"],
                    "note": "值班已接手"}


async def test_confirming_twice_runs_nothing_twice(store, core, session):
    d = desk(store, core)
    card = await d.propose(session, "m1", refs_for(store, session), ACK)

    results = await asyncio.gather(*(d.confirm(session, card["action_id"], 1) for _ in range(3)),
                                   return_exceptions=True)
    again = await d.confirm(session, card["action_id"], 1)

    assert len(core.writes) == 1
    receipts = {r["receipt"]["receipt_id"] for r in results if isinstance(r, dict)}
    conflicts = [r for r in results if isinstance(r, ActionConflict)]
    assert len(receipts) == 1 and all(c.code == "ACTION_IN_PROGRESS" for c in conflicts)
    assert again["receipt"]["receipt_id"] in receipts, "a retried confirm gets the same receipt back"


async def test_a_confirm_must_name_the_version_the_user_saw(store, core, session):
    d = desk(store, core)
    card = await d.propose(session, "m1", refs_for(store, session), ACK)

    with pytest.raises(ActionConflict) as e:
        await d.confirm(session, card["action_id"], 2)

    assert e.value.code == "ACTION_CHANGED" and core.writes == []


async def test_an_incident_that_changed_since_the_proposal_is_not_touched(store, core, session):
    d = desk(store, core)
    card = await d.propose(session, "m1", refs_for(store, session), ACK)
    # Meanwhile someone moves it on in the console.
    core.incidents[MYSQL_INCIDENT].update(status="investigating", version=2)

    done = await d.confirm(session, card["action_id"], 1)

    assert done["status"] == "blocked" and done["receipt"]["executed"] is False
    assert "被改过" in done["receipt"]["summary"] and core.writes == []


async def test_a_change_between_the_check_and_the_write_is_refused_by_core_service(store, core, session):
    d = desk(store, core)
    card = await d.propose(session, "m1", refs_for(store, session), ACK)
    core.write_fault = "race"

    done = await d.confirm(session, card["action_id"], 1)

    receipt = done["receipt"]
    assert done["status"] == "failed", "a refusal is a failure, never a success"
    assert receipt["response"]["code"] == "STALE_VERSION" and receipt["observed"]["status"] == "investigating"


@pytest.mark.parametrize("fault, status", [(500, "failed"), ("lie", "unknown"), ("lost", "succeeded"),
                                           ("dropped", "failed")])
async def test_the_receipt_is_what_happened_not_what_was_meant(store, core, session, fault, status):
    d = desk(store, core)
    card = await d.propose(session, "m1", refs_for(store, session), ACK)
    core.write_fault = fault

    done = await d.confirm(session, card["action_id"], 1)

    # 500: refused. lie: 200 but the incident did not move, so not a success. lost: no answer, but read back the
    # incident moved to the state asked for at the next version. dropped: no answer and nothing moved.
    assert done["status"] == status, done["receipt"]["summary"]
    assert core.incidents[MYSQL_INCIDENT]["status"] == ("acknowledged" if fault == "lost" else "open")


async def test_proposals_are_checked_before_anyone_sees_them(store, core, session):
    d = desk(store, core)
    refs = refs_for(store, session)

    cases = [
        ({**ACK, "kind": "restart_service"}, "没有这种操作"),
        ({**ACK, "params": {"to_status": "closed"}}, "params 不对"),
        ({**ACK, "params": {"to_status": "acknowledged", "force": True}}, "params 不对"),
        ({**ACK, "incident": "I9"}, "不是查到过的 Incident"),
        ({**ACK, "incident": "A1"}, "不是查到过的 Incident"),
        ({**ACK, "evidence": ["A7"]}, "不在查到的数据里"),
        ({**ACK, "evidence": ["A1", "A2"]}, "A2 不属于这个 Incident"),
        ({**ACK, "evidence": ["I2"]}, "I2 是另一个 Incident"),
        ({**ACK, "evidence": ["I1"]}, "至少要有一条"),
        ({**ACK, "evidence": []}, "evidence 要列出"),
        ({**ACK, "reason": " "}, "reason"),
    ]
    for args, message in cases:
        with pytest.raises(ProposalError, match=message):
            await d.propose(session, "m1", refs, args)
    core.incidents[MYSQL_INCIDENT]["status"] = "resolved"
    with pytest.raises(ProposalError, match="不能从「已解决」改为「已确认」"):
        await d.propose(session, "m1", refs, ACK)
    assert store.actions(session) == []


async def test_the_same_proposal_twice_is_one_card(store, core, session):
    d = desk(store, core)
    refs = refs_for(store, session)

    first = await d.propose(session, "m1", refs, ACK)
    second = await d.propose(session, "m2", refs, {**ACK, "evidence": ["A1"]})

    assert first["action_id"] == second["action_id"] and len(store.actions(session)) == 1


async def test_a_card_left_too_long_expires_instead_of_running(store, core, session):
    now = [datetime(2026, 11, 1, 10, 0, tzinfo=timezone.utc)]
    d = desk(store, core, ttl_s=600, clock=lambda: now[0])
    card = await d.propose(session, "m1", refs_for(store, session), ACK)
    now[0] += timedelta(minutes=11)

    done = await d.confirm(session, card["action_id"], 1)

    assert done["status"] == "expired" and core.writes == []


async def test_a_declined_card_cannot_be_confirmed(store, core, session):
    d = desk(store, core)
    card = await d.propose(session, "m1", refs_for(store, session), ACK)

    declined = await d.decline(session, card["action_id"], 1)
    with pytest.raises(ActionConflict) as e:
        await d.confirm(session, card["action_id"], 1)

    assert declined["status"] == "declined" and e.value.code == "ACTION_CLOSED" and core.writes == []


async def test_another_sessions_card_is_not_found(store, core, session):
    d = desk(store, core)
    card = await d.propose(session, "m1", refs_for(store, session), ACK)
    other = store.create_session()["session_id"]

    with pytest.raises(KeyError):
        await d.confirm(other, card["action_id"], 1)


async def test_without_a_writer_a_confirmed_card_is_blocked(store, core, session):
    d = ActionDesk(store, core.api(), None)
    card = await d.propose(session, "m1", refs_for(store, session), ACK)

    done = await d.confirm(session, card["action_id"], 1)

    assert done["status"] == "blocked" and "没有开启" in done["receipt"]["summary"] and core.writes == []


async def test_an_action_left_executing_by_a_restart_is_unknown_not_succeeded(tmp_path, core):
    path = tmp_path / "chat.sqlite3"
    first = ChatStore(path)
    session = first.create_session()["session_id"]
    card = await desk(first, core).propose(session, "m1", refs_for(first, session), ACK)
    assert first.move_action(card["action_id"], "proposed", 1, "executing")
    first.close()

    again = ChatStore(path)
    found = again.action(card["action_id"])
    again.close()

    assert found["status"] == "unknown" and "不知道是否已生效" in found["receipt"]["summary"]


async def test_request_analysis_is_an_action_too(store, core, session):
    d = desk(store, core)
    card = await d.propose(session, "m1", refs_for(store, session),
                           {"kind": "request_analysis", "incident": "I2", "evidence": ["A2"], "reason": "没有后台分析"})

    done = await d.confirm(session, card["action_id"], 1)

    assert done["status"] == "succeeded" and core.writes == [(f"/api/v1/incidents/{PAYMENT_INCIDENT}/analyses", None)]


# Through the chat: the model proposes, the answer says it waits, the next answer knows the receipt.

async def test_the_model_proposes_and_the_next_answer_is_told_the_receipt(store, core, session):
    propose = json.dumps({"kind": "transition_incident", "incident": "I1", "params": {"to_status": "acknowledged"},
                          "evidence": ["A1"], "reason": "MySQL 停机"})
    model = ScriptedStreamingModel(Script(tool_calls=[("propose_action", propose)]),
                                   Script(content="已提出把 [I1] 改为已确认，等待你确认。"),
                                   Script(content="已确认 [I1]。"), quick=[])
    chat = ChatAgent(core.api(), store, model, writer=core.writer())

    events = await collect(chat.ask(session, "刚才下单为什么失败？帮我把它标记为已确认"))

    [card] = of(events, "action")
    assert card["status"] == "proposed" and card["target"]["incident_id"] == MYSQL_INCIDENT
    tool_answer = json.loads(model.calls[1]["messages"][-1]["content"])
    assert tool_answer["status"] == "proposed" and "不要说已经执行" in tool_answer["note"]
    assert "照常按格式回答" in tool_answer["note"], "proposing does not take the place of the answer"
    assert core.writes == [] and "等待你确认" in answer(events)
    assert of(events, "done")[0]["actions"] == 1
    [_, saved] = store.messages(session)
    assert saved["trace"]["actions"][0]["action_id"] == card["action_id"]

    await chat.desk.confirm(session, card["action_id"], 1)
    await collect(chat.ask(session, "它现在是什么状态？"))

    system = model.calls[-1]["messages"][0]["content"]
    assert "本会话的操作（以回执为准）：把 I1 改为「已确认」：已执行成功" in system


async def test_a_proposal_that_fails_its_checks_goes_back_to_the_model(store, core, session):
    wrong = json.dumps({"kind": "transition_incident", "incident": "I1", "params": {"to_status": "acknowledged"},
                        "evidence": ["A9"], "reason": "x"})
    model = ScriptedStreamingModel(Script(tool_calls=[("propose_action", wrong)]), Script(content="没法提出。"),
                                   quick=[])
    chat = ChatAgent(core.api(), store, model, writer=core.writer())

    events = await collect(chat.ask(session, "刚才下单为什么失败？帮我把它标记为已确认"))

    assert of(events, "action") == [] and store.actions(session) == []
    assert "A9 不在查到的数据里" in model.calls[1]["messages"][-1]["content"]
    [step] = [s for s in of(events, "step") if s["kind"] == "tool" and s["status"] != "running"]
    assert step["status"] == "error"


async def test_the_answer_written_before_a_proposal_is_kept(store, core, session):
    # The model answers the question, then proposes in the same turn: that text is the answer, not a note.
    propose = json.dumps({"kind": "transition_incident", "incident": "I1", "params": {"to_status": "acknowledged"},
                          "evidence": ["A1"], "reason": "MySQL 停机"})
    model = ScriptedStreamingModel(Script(content="**结论**：shop-mysql 停机 [I1]。",
                                          tool_calls=[("propose_action", propose)]),
                                   Script(content="已提出把 [I1] 改为已确认，等待你确认。"), quick=[])
    chat = ChatAgent(core.api(), store, model, writer=core.writer())

    events = await collect(chat.ask(session, "刚才下单为什么失败？帮我把它标记为已确认"))

    assert of(events, "interim") == []
    assert answer(events) == "**结论**：shop-mysql 停机 [I1]。\n\n已提出把 [I1] 改为已确认，等待你确认。"
    assert store.messages(session)[-1]["content"] == answer(events)
    assert "已经原样给了用户" in json.loads(model.calls[1]["messages"][-1]["content"])["note"]


async def test_text_before_a_lookup_is_still_a_note(store, core, session):
    model = ScriptedStreamingModel(Script(content="我先查一下日志。", tool_calls=[("search_logs", '{"service": "order-service"}'),
                                                                     ("propose_action", "{}")]),
                                   Script(content="结论 [I1]"), quick=[])
    chat = ChatAgent(core.api(), store, model, writer=core.writer())

    events = await collect(chat.ask(session, "刚才下单为什么失败？"))

    assert [d["text"] for d in of(events, "interim")] == ["我先查一下日志。"] and answer(events) == "结论 [I1]"


async def test_an_answer_that_says_the_waiting_card_was_run_is_corrected(store, core, session):
    propose = json.dumps({"kind": "transition_incident", "incident": "I1", "params": {"to_status": "resolved"},
                          "evidence": ["A1"], "reason": "用户说已修好"})
    model = ScriptedStreamingModel(Script(tool_calls=[("propose_action", propose)]),
                                   Script(content="我按你的要求把 [I1] 标记为已解决了。"), quick=[])
    chat = ChatAgent(core.api(), store, model, writer=core.writer())

    events = await collect(chat.ask(session, "刚才下单为什么失败？已经修好了，标记为已解决"))

    assert answer(events).endswith("上面的操作还没有执行，卡片在等待你确认；做没做成以确认后的回执为准。")
    assert core.writes == []


async def test_refs_follow_the_incidents_order_not_which_fetch_finished_first(store, session):
    import time

    class SlowFirst(ChatCore):
        def handle(self, request):
            if request.url.path == f"/api/v1/incidents/{MYSQL_INCIDENT}/alerts":
                time.sleep(0.2)
            return super().handle(request)

    core = SlowFirst()
    core.add_payment_incident()
    await collect(ChatAgent(core.api(), store, None).ask(session, "刚才下单为什么失败？"))

    refs = store.session(session)["refs"]
    assert refs["I1"]["id"] == MYSQL_INCIDENT and refs["A1"]["incident_id"] == MYSQL_INCIDENT
    assert refs["A3"]["id"] == PAYMENT_ALERT, "the second incident's alerts come after the first's"
