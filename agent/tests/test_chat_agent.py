import asyncio
import json

import pytest

from chat_support import (DB_ALERT, MYSQL_INCIDENT, ChatCore, Script, ScriptedStreamingModel, answer, collect, of)
from ops_agent.chat.agent import ChatAgent
from ops_agent.chat.evidence import RefTable
from ops_agent.chat.memory import ChatStore


@pytest.fixture
def core():
    return ChatCore()


@pytest.fixture
def store(tmp_path):
    s = ChatStore(tmp_path / "chat.sqlite3")
    yield s
    s.close()


def agent(core, store, model=None, **kw) -> ChatAgent:
    return ChatAgent(core.api(), store, model, **kw)


def steps(events) -> list[tuple[str, str, str]]:
    """The finished steps: kind, title, status."""
    return [(d["kind"], d["title"], d["status"]) for d in of(events, "step") if d["status"] != "running"]


async def test_without_a_model_the_answer_is_the_rules_attribution_with_refs(core, store):
    session = store.create_session()

    events = await collect(agent(core, store).ask(session["session_id"], "刚才下单为什么失败？", "Asia/Shanghai"))

    assert [e for e, _ in events][:2] == ["meta", "route"]
    route = of(events, "route")[0]
    assert (route["route"], route["method"]) == ("ops_diagnosis", "rule")
    assert route["entities"]["services"] == ["order-service"] and route["entities"]["minutes"] == 30
    assert [s[0] for s in steps(events)] == ["locate", "fetch", "check"]
    text = answer(events)
    assert "[I1]" in text and "shop-mysql" in text and "[A1]" in text and "没有使用大模型" in text
    assert all(c["verified"] for c in of(events, "citations")[0]["items"])
    done = of(events, "done")[0]
    assert done["status"] == "completed" and done["route"] == "ops_diagnosis"

    saved = store.session(session["session_id"])
    assert saved["title"] == "刚才下单为什么失败？"
    assert saved["focus"]["incidents"][0]["incident_id"] == MYSQL_INCIDENT
    [user, assistant] = store.messages(session["session_id"])
    assert user["content"] == "刚才下单为什么失败？" and assistant["content"] == text
    assert assistant["trace"]["route"]["route"] == "ops_diagnosis"
    assert [i["kind"] for i in assistant["trace"]["items"]] == ["locate", "fetch", "check"]


async def test_a_follow_up_is_about_the_incident_under_discussion(core, store):
    session = store.create_session()["session_id"]
    chat = agent(core, store)
    await collect(chat.ask(session, "刚才下单为什么失败？"))

    events = await collect(chat.ask(session, "它影响了哪些服务？"))

    locate = [d for d in of(events, "step") if d["kind"] == "locate" and d["status"] == "done"][0]
    assert locate["result"].startswith("上文讨论的 Incident：1 个")
    assert "[I1]" in answer(events), "the same incident keeps its ref"


async def test_an_empty_window_is_widened_to_a_day(core, store):
    del core.incidents[MYSQL_INCIDENT]
    session = store.create_session()["session_id"]

    events = await collect(agent(core, store).ask(session, "payment-service 怎么了"))

    locate = [d for d in of(events, "step") if d["kind"] == "locate" and d["status"] == "done"][0]
    assert "扩大到 1 天" in locate["result"] and "1 个" in locate["result"]


async def test_the_model_reasons_over_the_fetched_evidence_and_may_look_further(core, store):
    model = ScriptedStreamingModel(
        Script(reasoning="规则指向 shop-mysql，再看看同时段别的告警。", tool_calls=[("search_alerts", '{"minutes": 30}')]),
        Script(reasoning="同时段没有别的问题。",
               content="**结论**：shop-mysql 不可用 [I1]。\n**依据**：数据库连接失败 [A1, E1]；还有 [A9]。"))
    session = store.create_session()["session_id"]

    events = await collect(agent(core, store, model).ask(session, "刚才下单为什么失败？"))

    names = [e for e, _ in events]
    assert names.index("thinking") < names.index("answer")
    assert [s[0] for s in steps(events)] == ["locate", "fetch", "tool", "check"]
    assert steps(events)[-1] == ("check", "核对引用", "error")
    cites = {c["ref"]: c for c in of(events, "citations")[0]["items"]}
    assert cites["I1"]["id"] == MYSQL_INCIDENT and cites["A1"]["id"] == DB_ALERT and cites["E1"]["verified"]
    assert cites["A9"] == {"ref": "A9", "verified": False}

    first = model.calls[0]
    data = first["messages"][-1]["content"]
    assert "rule_attribution" in data and "background_analysis" in data and "[I1]" not in data
    assert MYSQL_INCIDENT not in data, "the model sees refs, not ids"
    assert first["tools"] == ["list_incidents", "get_incident", "search_alerts", "search_logs", "count_logs",
                              "get_topology"]
    assert model.calls[1]["messages"][-1]["role"] == "tool"
    assert model.calls[1]["messages"][-2]["reasoning_content"].startswith("规则指向")

    [_, assistant] = store.messages(session)
    kinds = [i["type"] for i in assistant["trace"]["items"]]
    assert kinds == ["step", "step", "thought", "step", "thought", "step"]
    assert assistant["trace"]["metrics"]["tool_calls"] == 1


async def test_text_before_a_tool_call_is_a_note_not_the_answer(core, store):
    model = ScriptedStreamingModel(Script(content="我先查一下拓扑。", tool_calls=[("get_topology", "{}")]),
                                   Script(content="结论 [I1]"))
    session = store.create_session()["session_id"]

    events = await collect(agent(core, store, model).ask(session, "刚才下单为什么失败？"))

    assert of(events, "interim") == [{"text": "我先查一下拓扑。"}]
    assert answer(events) == "结论 [I1]"
    [_, assistant] = store.messages(session)
    assert assistant["content"] == "结论 [I1]"
    assert {"type": "thought", "text": "我先查一下拓扑。", "interim": True} in assistant["trace"]["items"]


async def test_the_tool_budget_ends_with_an_answer(core, store):
    model = ScriptedStreamingModel(*[Script(tool_calls=[("get_topology", "{}")]) for _ in range(2)],
                                   Script(content="够了 [I1]"))
    session = store.create_session()["session_id"]

    events = await collect(agent(core, store, model, max_tool_calls=2).ask(session, "刚才下单为什么失败？"))

    assert answer(events) == "够了 [I1]"
    assert [c["tools"] for c in model.calls][-1] == [], "no tools once the budget is spent"
    assert of(events, "done")[0]["tool_calls"] == 2


async def test_when_the_model_fails_the_answer_comes_from_the_fetched_data(core, store):
    model = ScriptedStreamingModel(Script(fail=TimeoutError("read timed out")))
    session = store.create_session()["session_id"]

    events = await collect(agent(core, store, model).ask(session, "刚才下单为什么失败？"))

    assert ("fallback", "模型调用失败，改为根据查到的数据回答", "done") in steps(events)
    assert "大模型调用失败" in answer(events) and "shop-mysql" in answer(events)
    assert of(events, "done")[0]["status"] == "completed" and of(events, "done")[0]["degraded"]


async def test_a_status_question_lists_incidents_and_alerts(core, store):
    session = store.create_session()["session_id"]

    events = await collect(agent(core, store).ask(session, "最近一小时有哪些告警"))

    assert of(events, "route")[0]["route"] == "ops_query"
    titles = [s[1] for s in steps(events)]
    assert titles[0] == "定位相关的 Incident" and titles[-1] == "核对引用"
    assert sorted(titles[1:-1]) == ["查询最近的告警", "读取 I1 的告警、相关服务的日志、规则归因和后台分析"], "in parallel"
    text = answer(events)
    assert "有 1 个 Incident" in text and "（2 分钟前）" in text and "规则指向 shop-mysql" in text


async def test_knowledge_and_small_talk_skip_the_orchestration(core, store):
    model = ScriptedStreamingModel(Script(reasoning="击穿是热点 key 过期。", content="缓存击穿是……"), quick=["你好！"])
    chat = agent(core, store, model)
    session = store.create_session()["session_id"]

    knowledge = await collect(chat.ask(session, "什么是缓存击穿"))
    hello = await collect(chat.ask(session, "你好"))
    poem = await collect(chat.ask(session, "帮我写一首诗"))

    assert not of(knowledge, "step") and answer(knowledge) == "缓存击穿是……" and of(knowledge, "thinking")
    assert answer(hello) == "你好！"
    assert "超出了我的范围" in answer(poem) and not model.turns
    assert not any(r.url.path.startswith("/api/v1/incidents") for r in core.requests)


async def test_when_core_service_is_down_the_answer_says_so(core, store):
    core.down = True
    session = store.create_session()["session_id"]

    events = await collect(agent(core, store).ask(session, "刚才下单为什么失败？"))

    assert "core-service 不可用" in of(events, "error")[0]["message"]
    assert of(events, "done")[0]["status"] == "failed"
    assert store.messages(session)[-1]["status"] == "failed"


async def test_a_cancelled_answer_is_kept_as_far_as_it_got(core, store):
    release = asyncio.Event()

    class Slow(ScriptedStreamingModel):
        async def stream(self, messages, tools=None, *, on_reasoning=None, on_content=None, thinking=None):
            on_content("MySQL 好像")
            await release.wait()
            raise AssertionError("not reached")

    session = store.create_session()["session_id"]
    events = agent(core, store, Slow()).ask(session, "刚才下单为什么失败？")
    async for event, data in events:
        if event == "answer":
            break
    await events.aclose()

    [_, assistant] = store.messages(session)
    assert assistant["status"] == "cancelled" and assistant["content"] == "MySQL 好像"


async def test_older_turns_are_folded_into_a_summary(core, store):
    model = ScriptedStreamingModel(*[Script(content=f"回答 {n} [I1]") for n in range(8)], quick=["摘要：讨论了 I1"])
    chat = agent(core, store, model, history_turns=2)
    session = store.create_session()["session_id"]

    for n in range(6):
        await collect(chat.ask(session, f"刚才下单为什么失败？第 {n} 次"))
    await asyncio.gather(*chat._background)

    saved = store.session(session)
    assert saved["summary"] == "摘要：讨论了 I1" and saved["summarized_through"] == 8
    events = await collect(chat.ask(session, "刚才下单为什么失败？再问一次"))
    system = model.calls[-1]["messages"][0]["content"]
    assert "摘要：讨论了 I1" in system and "当前话题：[I1]" in system
    history = [m for m in model.calls[-1]["messages"][1:-1]]
    assert len(history) == 4, "two turns word for word"
    assert answer(events) == "回答 6 [I1]"


def test_refs_stay_the_same_for_the_whole_session():
    table = RefTable()
    a = table.ref("alert", "X-1", "first")
    i = table.ref("incident", "Y-1", "incident")
    again = RefTable(json.loads(json.dumps(table.as_json())))

    assert (a, i) == ("A1", "I1")
    assert again.ref("alert", "x-1", "first") == "A1" and again.ref("alert", "X-2", "second") == "A2"
    assert again.id_of("I1", "incident") == "Y-1" and again.id_of(" Y-9 ", "incident") == "Y-9"
    with pytest.raises(ValueError):
        again.id_of("A1", "incident")


async def test_without_prefetch_the_model_starts_from_the_list_and_fetches_the_rest(core, store):
    model = ScriptedStreamingModel(Script(tool_calls=[("get_incident", '{"incident": "I1"}')]),
                                   Script(content="结论 [I1]"), Script(fail=TimeoutError("slow")))
    chat = agent(core, store, model, prefetch=False)
    session = store.create_session()["session_id"]

    events = await collect(chat.ask(session, "刚才下单为什么失败？"))

    data = model.calls[0]["messages"][-1]["content"]
    assert "rule_attribution" not in data and "需要用工具查" in data
    assert [s[0] for s in steps(events)] == ["locate", "tool", "check"]
    assert "rule_attribution" in model.calls[1]["messages"][-1]["content"]

    fallback = await collect(chat.ask(session, "刚才下单为什么失败？"))
    assert "有这些 Incident" in answer(fallback) and "[I1]" in answer(fallback)


async def test_the_user_chooses_whether_the_model_thinks(core, store):
    model = ScriptedStreamingModel(Script(content="结论 [I1]"), Script(content="结论 [I1]"))
    chat = agent(core, store, model)
    session = store.create_session()["session_id"]

    await collect(chat.ask(session, "刚才下单为什么失败？", thinking=True))
    done = of(await collect(chat.ask(session, "刚才下单为什么失败？")), "done")[0]

    assert [c["thinking"] for c in model.calls] == [True, None]
    assert done["thinking"] is None and store.messages(session)[1]["trace"]["metrics"]["thinking"] is True


async def test_the_model_is_told_how_the_incidents_lie_in_time(core, store):
    model = ScriptedStreamingModel(Script(content="两个问题 [I1] [I2]"))
    session = store.create_session()["session_id"]

    await collect(agent(core, store, model).ask(session, "今天有几个故障，是同一个原因吗？"))

    data = model.calls[0]["messages"][-1]["content"]
    timing = data[data.index("incident_timing"):]
    assert "I1：" in timing and "在 I2 最后一条告警之后 2 小时 54 分才开始" in timing



def pack(call: dict) -> dict:
    """The data the orchestration handed the model, from its last message."""
    content = call["messages"][-1]["content"]
    return json.loads(content[content.index("```json") + 7:content.rindex("```")])


async def test_the_services_own_logs_come_with_the_incident_and_can_be_searched(core, store):
    model = ScriptedStreamingModel(
        Script(tool_calls=[("search_logs", '{"service": "payment-service", "incident": "I1"}')]),
        Script(content="payment-service 仍在正常扣款 [E3]，问题在数据库 [I1]"))
    session = store.create_session()["session_id"]

    events = await collect(agent(core, store, model).ask(session, "刚才下单为什么失败？"))

    logged = pack(model.calls[0])["incidents"][0]["service_logs"]
    assert list(logged) == ["order-service", "payment-service"], "subjects first, databases left out"
    assert logged["payment-service"]["events"] == [{"event_type": "payment_succeeded", "count": 40,
                                                    "severity": "info", "example": "Payment succeeded"}]
    errors = [r for r in core.requests if r.url.path == "/api/v1/logs" and r.url.params.get("min_severity")]
    assert {r.url.params["min_severity"] for r in errors} == {"low"}
    counts = [r for r in core.requests if r.url.path == "/api/v1/logs/counts"]
    assert {r.url.params["service"] for r in counts} == {"order-service", "payment-service"}

    found = json.loads(model.calls[1]["messages"][-1]["content"])
    assert found["lines"][0]["ref"] == "E3" and found["lines"][0]["message"] == "Payment succeeded"
    cites = {c["ref"]: c for c in of(events, "citations")[0]["items"]}
    assert cites["E3"]["verified"] and cites["E3"]["incident_id"] == MYSQL_INCIDENT
    assert ("tool", "查询日志", "done") in steps(events)
