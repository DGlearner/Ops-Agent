"""Asking before answering: a questionnaire ends the answer, the reply goes on from that pause point."""

import json

import pytest

from chat_support import (MYSQL_INCIDENT, PAYMENT_INCIDENT, ChatCore, Script, ScriptedStreamingModel, answer, collect,
                          of)
from ops_agent.chat.agent import ChatAgent, PauseError
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


def kinds(events) -> list[str]:
    return [d["kind"] for d in of(events, "step") if d["status"] != "running"]


async def test_two_unrelated_faults_and_no_hint_which_one_ask_first(core, store):
    chat = ChatAgent(core.api(), store, None)
    session = store.create_session()["session_id"]

    events = await collect(chat.ask(session, "刚才为什么报错？"))

    [q] = of(events, "questionnaire")
    [target] = q["questions"]
    assert target["kind"] == "single" and not target["allow_text"]
    assert [o["id"] for o in target["options"]] == ["I1", "I2", "all"]
    assert {o.get("incident_id") for o in target["options"][:2]} == {MYSQL_INCIDENT, PAYMENT_INCIDENT}
    assert kinds(events) == ["locate"], "nothing fetched before the user says which"
    assert "先确认你问的是哪个" in answer(events) and of(events, "done")[0]["paused"] is True
    assert store.open_pause(session)["pause_id"] == q["pause_id"]
    [_, saved] = store.messages(session)
    assert saved["trace"]["questionnaire"]["pause_id"] == q["pause_id"]


async def test_the_reply_goes_on_from_the_pause_with_the_incident_picked(core, store):
    chat = ChatAgent(core.api(), store, None)
    session = store.create_session()["session_id"]
    q = of(await collect(chat.ask(session, "刚才为什么报错？")), "questionnaire")[0]
    payment = next(o["id"] for o in q["questions"][0]["options"] if o.get("incident_id") == PAYMENT_INCIDENT)

    plan = chat.resume_plan(session, q["pause_id"], [{"id": "target", "choices": [payment]}])
    events = await collect(chat.ask(session, "\n".join(plan["lines"]), plan=plan))

    route = of(events, "route")[0]
    assert route["resumed"] is True and route["route"] == "ops_diagnosis"
    locate = next(d for d in of(events, "step") if d["kind"] == "locate" and d["status"] == "done")
    assert locate["result"].startswith("你选择的 Incident：1 个")
    assert kinds(events) == ["locate", "fetch", "check"] and of(events, "questionnaire") == []
    assert "payment-service answered with 503" in answer(events)
    user = store.messages(session)[2]
    assert user["role"] == "user" and "payment-service answered with 503" in user["content"]
    assert store.pause(q["pause_id"])["status"] == "answered"


async def test_a_questionnaire_is_answered_once(core, store):
    chat = ChatAgent(core.api(), store, None)
    session = store.create_session()["session_id"]
    q = of(await collect(chat.ask(session, "刚才为什么报错？")), "questionnaire")[0]

    chat.resume_plan(session, q["pause_id"], [{"id": "target", "choices": ["all"]}])
    with pytest.raises(PauseError) as e:
        chat.resume_plan(session, q["pause_id"], [{"id": "target", "choices": ["all"]}])

    assert (e.value.status, e.value.code) == (409, "PAUSE_CLOSED")


@pytest.mark.parametrize("answers, message", [
    ([{"id": "target", "choices": ["I7"]}], "没有这个选项"),
    ([{"id": "target", "choices": ["I1", "I2"]}], "只能选一个"),
    ([{"id": "target", "text": "随便"}], "只能选选项"),
    ([{"id": "who", "choices": ["I1"]}], "没有这些问题"),
    ([{"id": "target"}], "至少回答一个问题"),
])
async def test_answers_must_fit_the_questions(core, store, answers, message):
    chat = ChatAgent(core.api(), store, None)
    session = store.create_session()["session_id"]
    q = of(await collect(chat.ask(session, "刚才为什么报错？")), "questionnaire")[0]

    with pytest.raises(PauseError, match=message) as e:
        chat.resume_plan(session, q["pause_id"], answers)

    assert e.value.status == 400
    assert store.pause(q["pause_id"])["status"] == "open", "a wrong answer does not use up the questionnaire"


async def test_all_of_them_fetches_each(core, store):
    chat = ChatAgent(core.api(), store, None)
    session = store.create_session()["session_id"]
    q = of(await collect(chat.ask(session, "刚才为什么报错？")), "questionnaire")[0]

    plan = chat.resume_plan(session, q["pause_id"], [{"id": "target", "choices": ["all"]}])
    events = await collect(chat.ask(session, "都看看", plan=plan))

    assert kinds(events) == ["locate", "fetch", "fetch", "check"]


async def test_asking_something_else_supersedes_the_questionnaire(core, store):
    chat = ChatAgent(core.api(), store, None)
    session = store.create_session()["session_id"]
    q = of(await collect(chat.ask(session, "刚才为什么报错？")), "questionnaire")[0]

    await collect(chat.ask(session, "最近一小时有哪些告警？"))

    assert store.pause(q["pause_id"])["status"] == "superseded"
    with pytest.raises(PauseError):
        chat.resume_plan(session, q["pause_id"], [{"id": "target", "choices": ["all"]}])


@pytest.mark.parametrize("question", ["刚才有几个问题，为什么报错？", "刚才下单为什么失败？", "刚才报错是同一个原因吗？"])
async def test_no_question_when_the_answer_is_all_of_them_or_the_question_says_which(core, store, question):
    chat = ChatAgent(core.api(), store, None)
    session = store.create_session()["session_id"]

    events = await collect(chat.ask(session, question))

    assert of(events, "questionnaire") == []


async def test_incidents_on_the_same_component_are_not_asked_about(store):
    core = ChatCore()
    core.add_payment_incident()
    # Both point at payment-service: one problem to the user.
    core.incidents[MYSQL_INCIDENT].update(subjects=["payment-service"], shared_dependencies=[])
    chat = ChatAgent(core.api(), store, None)
    session = store.create_session()["session_id"]

    events = await collect(chat.ask(session, "刚才为什么报错？"))

    assert of(events, "questionnaire") == []


async def test_the_model_may_ask_once_and_goes_on_with_the_answers(store):
    core = ChatCore()
    ask = json.dumps({"questions": [{"id": "seen", "title": "你看到的是什么现象？", "options": ["下单报错", "页面很慢"]},
                                    {"id": "when", "title": "大概几点开始的？"}]})
    model = ScriptedStreamingModel(Script(tool_calls=[("ask_user", ask)]), Script(content="是 [I1] 的数据库问题。"),
                                   quick=[])
    chat = ChatAgent(core.api(), store, model)
    session = store.create_session()["session_id"]

    events = await collect(chat.ask(session, "刚才下单为什么失败？"))

    [q] = of(events, "questionnaire")
    assert q["source"] == "model" and [x["id"] for x in q["questions"]] == ["seen", "when"]
    assert q["questions"][0]["options"] == [{"id": "o1", "label": "下单报错"}, {"id": "o2", "label": "页面很慢"}]
    assert q["questions"][1]["allow_text"] is True

    plan = chat.resume_plan(session, q["pause_id"], [{"id": "seen", "choices": ["o1"]},
                                                       {"id": "when", "text": "十点左右"}])
    await collect(chat.ask(session, "\n".join(plan["lines"]), plan=plan))

    resumed = model.calls[-1]
    assert "ask_user" not in resumed["tools"], "one round of questions per question"
    asked = resumed["messages"][-1]["content"]
    assert "刚才下单为什么失败？" in asked and "你看到的是什么现象？ 下单报错" in asked and "十点左右" in asked


async def test_questions_that_do_not_fit_are_dropped_and_the_model_answers(store):
    core = ChatCore()
    bad = json.dumps({"questions": [{"title": f"问题 {n}"} for n in range(6)]})
    model = ScriptedStreamingModel(Script(tool_calls=[("ask_user", bad)]), Script(content="直接回答 [I1]。"), quick=[])
    chat = ChatAgent(core.api(), store, model)
    session = store.create_session()["session_id"]

    events = await collect(chat.ask(session, "刚才下单为什么失败？"))

    assert of(events, "questionnaire") == [] and answer(events) == "直接回答 [I1]。"
