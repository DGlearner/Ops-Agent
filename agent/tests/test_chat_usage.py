"""What a run spent, and the way from a run to its verdict (docs/agent-design.md §15)."""

import asyncio
import json
import sqlite3

import httpx
import openai
import pytest
from fastapi.testclient import TestClient

from case_support import CaseCore
from chat_support import MYSQL_INCIDENT, Script, ScriptedStreamingModel, collect, of
from ops_agent.chat.agent import ChatAgent
from ops_agent.chat.llm import OpenAIStreamingChat
from ops_agent.chat.memory import ChatStore
from ops_agent.chat.outcome_eval import chain_problems
from ops_agent.chat.server import create_app
from ops_agent.chat.usage import Ledger, Metered
from ops_agent.core_api import CoreApiError
from ops_agent.verify import Verifier

HANDLE = "下单失败了，帮我处理一下"
ROUTED = '{"route": "ops_diagnosis", "reason": "x", "services": ["order-service"]}'


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


def run_of(store, events):
    return store.run(of(events, "done")[0]["run_id"])


async def test_every_model_call_and_tool_step_of_a_run_is_in_its_account(core, store, session):
    model = ScriptedStreamingModel(
        Script(tool_calls=[("get_topology", "{}")], usage={"prompt": 100, "completion": 20, "cached": 80}),
        Script(content="查好了 [I1]", usage={"prompt": 150, "completion": 30, "reasoning": 12}),
        quick=[ROUTED], quick_usage={"prompt": 10, "completion": 2})
    events = await collect(agent(core, store, model).ask(session, "下单失败了，帮我看看"))

    usage = run_of(store, events)["usage"]
    calls = [s for s in usage["spans"] if s["kind"] == "model"]
    assert [(c["tier"], c["purpose"], c["status"]) for c in calls] == [
        ("quick", "route", "ok"), ("main", "answer", "ok"), ("main", "answer", "ok")]
    assert (usage["prompt_tokens"], usage["completion_tokens"], usage["total_tokens"]) == (260, 52, 312)
    assert (usage["cached_tokens"], usage["reasoning_tokens"]) == (80, 12)
    assert usage["calls_without_usage"] == 0 and usage["tool_steps"] == 1
    assert "first_token_ms" in calls[2], "the answer was streamed"
    steps = [s for s in usage["spans"] if s["kind"] == "step"]
    assert "查看服务依赖" in [s["name"] for s in steps] and all(s["duration_ms"] is not None for s in steps)
    assert [s["at_ms"] for s in usage["spans"]] == sorted(s["at_ms"] for s in usage["spans"])
    assert usage["wall_ms"] >= max(s["at_ms"] + s["duration_ms"] for s in usage["spans"])

    # The totals travel with the answer, the spans stay in the run.
    done = of(events, "done")[0]
    assert done["total_tokens"] == 312 and "spans" not in done
    assert store.messages(session)[-1]["trace"]["metrics"]["run_id"] == done["run_id"]


async def test_a_call_with_no_usage_reported_is_not_counted_as_zero(core, store, session):
    model = ScriptedStreamingModel(Script(content="好 [I1]"), quick=[ROUTED])
    usage = run_of(store, await collect(agent(core, store, model).ask(session, "下单失败了，帮我看看")))["usage"]

    assert usage["model_calls"] == 2 and usage["calls_without_usage"] == 2
    assert usage["total_tokens"] is None and usage["prompt_tokens"] is None


async def test_without_a_model_nothing_is_spent_but_the_steps_are_timed(core, store, session):
    usage = run_of(store, await collect(agent(core, store).ask(session, HANDLE)))["usage"]

    assert usage["model_calls"] == 0 and usage["total_tokens"] is None and usage["model_ms"] == 0
    assert [s for s in usage["spans"] if s["kind"] == "step"]


async def test_a_failed_call_is_in_the_account_and_the_fallback_answer_still_comes(core, store, session):
    model = ScriptedStreamingModel(Script(fail=RuntimeError("402")), quick=[ROUTED])
    events = await collect(agent(core, store, model).ask(session, "下单失败了，帮我看看"))

    run = run_of(store, events)
    failed = [s for s in run["usage"]["spans"] if s["kind"] == "model" and s["status"] == "error"]
    assert run["status"] == "degraded" and failed[0]["error"] == "RuntimeError" and failed[0]["purpose"] == "answer"


async def test_a_run_cut_off_keeps_what_it_spent_so_far(core, store, session):
    release = asyncio.Event()

    class Slow(ScriptedStreamingModel):
        async def stream(self, messages, tools=None, *, on_reasoning=None, on_content=None, thinking=None):
            on_content("MySQL 好像")
            await release.wait()
            raise AssertionError("not reached")

    events = agent(core, store, Slow(quick=[ROUTED], quick_usage={"prompt": 10, "completion": 2})).ask(
        session, "下单失败了，帮我看看")
    async for event, _ in events:
        if event == "answer":
            break
    await events.aclose()

    [run] = store.runs(session)
    spans = [s for s in run["usage"]["spans"] if s["kind"] == "model"]
    assert run["status"] == "cancelled"
    assert [s["status"] for s in spans] == ["ok", "cancelled"] and spans[1]["duration_ms"] is not None
    assert run["usage"]["total_tokens"] == 12 and run["usage"]["calls_without_usage"] == 1


def test_a_span_left_open_when_the_run_ends_was_cut_off():
    now = [0.0]
    ledger = Ledger(lambda: now[0])
    span = ledger.begin("model", "m")
    now[0] = 1.5
    ledger.settle()
    assert span["status"] == "cancelled" and span["duration_ms"] == 1500


async def test_metered_leaves_an_unstreamed_quick_call_unstreamed():
    seen = {}

    class Model:
        name = "m"

        async def quick(self, messages, *, json_output=False, timeout_s=None, on_content=None, on_usage=None):
            seen.update(on_content=on_content, timeout_s=timeout_s, json_output=json_output)
            return "{}"

    await Metered(Model(), Ledger()).quick([], json_output=True, timeout_s=3)
    assert seen == {"on_content": None, "timeout_s": 3, "json_output": True}


def _sse(*chunks: dict) -> str:
    return "".join(f"data: {json.dumps(c)}\n\n" for c in chunks) + "data: [DONE]\n\n"


def _chunk(delta: dict, usage: dict | None = None) -> dict:
    return {"id": "c", "object": "chat.completion.chunk", "created": 1, "model": "m",
            "choices": [{"index": 0, "delta": delta, "finish_reason": None}] if delta else [], "usage": usage}


DEEPSEEK_USAGE = {"prompt_tokens": 100, "completion_tokens": 40, "total_tokens": 140, "prompt_cache_hit_tokens": 60,
                  "prompt_cache_miss_tokens": 40, "completion_tokens_details": {"reasoning_tokens": 25}}


def _model(handler) -> OpenAIStreamingChat:
    model = OpenAIStreamingChat("http://llm/v1", "k", "m")
    model._client = openai.AsyncOpenAI(base_url="http://llm/v1", api_key="k", max_retries=0,
                                       http_client=httpx.AsyncClient(transport=httpx.MockTransport(handler)))
    return model


async def test_usage_is_read_from_what_the_provider_sends_when_streaming():
    asked = []

    def handler(request):
        asked.append(json.loads(request.content))
        return httpx.Response(200, headers={"content-type": "text/event-stream"},
                              text=_sse(_chunk({"content": "好"}), _chunk({}, DEEPSEEK_USAGE)))

    model = _model(handler)
    turn = await model.stream([{"role": "user", "content": "x"}])
    assert turn.usage == {"prompt": 100, "completion": 40, "cached": 60, "reasoning": 25}

    told = []
    await model.quick([{"role": "user", "content": "x"}], on_content=lambda d: None, on_usage=told.append)
    assert told == [turn.usage]
    assert all(a["stream_options"] == {"include_usage": True} for a in asked)


async def test_usage_is_read_from_an_unstreamed_quick_call_too():
    def handler(request):
        return httpx.Response(200, json={
            "id": "c", "object": "chat.completion", "created": 1, "model": "m",
            "choices": [{"index": 0, "finish_reason": "stop", "message": {"role": "assistant", "content": "{}"}}],
            "usage": {"prompt_tokens": 9, "completion_tokens": 1, "total_tokens": 10,
                      "prompt_tokens_details": {"cached_tokens": 4}}})

    told = []
    assert await _model(handler).quick([{"role": "user", "content": "x"}], on_usage=told.append) == "{}"
    assert told == [{"prompt": 9, "completion": 1, "cached": 4}]


def test_a_file_from_before_usage_was_kept_gets_the_column(tmp_path):
    path = tmp_path / "old.sqlite3"
    ChatStore(path).close()
    with sqlite3.connect(path) as db:
        db.execute("DROP INDEX run_message")
        db.execute("ALTER TABLE run DROP COLUMN usage")
        db.execute("INSERT INTO session (session_id, title, created_at, updated_at) VALUES ('s', 't', 'x', 'x')")
        db.execute("INSERT INTO run (run_id, session_id, trigger, status, message_id, started_at) "
                   "VALUES ('r', 's', 'user_message', 'answered', 'm', 'x')")
    db.close()

    store = ChatStore(path)
    assert store.run("r")["usage"] is None
    store.close()


async def test_a_remediation_can_be_followed_from_the_run_to_the_verdict(core, store, session):
    chat = agent(core, store)
    events = await collect(chat.ask(session, HANDLE))
    run = run_of(store, events)
    [card] = of(events, "action")
    await chat.desk.confirm(session, card["action_id"], card["version"], card["payload_digest"])

    # core-service was told, and passed it on to the verification the worker claims.
    [done] = core.remediations
    assert done["run_id"] == run["run_id"] and done["session_id"] == session
    assert done["snapshot_digest"] == run["snapshot_digest"] and run["snapshot_digest"]
    core.alerts = {}
    core.log_counts["order-service"] = [{"key": "order_paid", "count": 12}]
    judged = Verifier(core.api(), core.writer(), "w").once()
    assert judged["verification"]["run_id"] == run["run_id"] and judged["result"]["run_id"] == run["run_id"]

    chain = await chat.chain(run["run_id"])
    assert chain["question"] == HANDLE and chain["answer"]["status"] == "completed" and chain["core_error"] is None
    [shown] = chain["actions"]
    assert shown["action_id"] == card["action_id"] and shown["receipt"]["status"] == "succeeded"
    assert shown["remediation"]["remediation_id"] == done["remediation_id"]
    assert [v["status"] for v in shown["verifications"]] == ["done"]
    assert chain_problems(chain, card["action_id"]) == []

    # What this run wrote on the case, signed with it.
    [case] = chain["cases"]
    assert case["case_id"] == core.case_of(MYSQL_INCIDENT)["case_id"]
    assert [e["kind"] for e in case["entries"]] == ["fact"]
    assert all(e["run_id"] == run["run_id"] for e in case["entries"])


async def test_the_chain_says_what_is_missing(core, store, session):
    chat = agent(core, store)
    events = await collect(chat.ask(session, HANDLE))
    run_id = of(events, "done")[0]["run_id"]
    [card] = of(events, "action")

    chain = await chat.chain(run_id)
    assert chain["actions"][0]["remediation"] is None
    assert chain_problems(chain, card["action_id"]) == ["卡片没有对应的处置"]
    assert chain_problems(chain, "another") == ["卡片不在这一轮里"]

    await chat.desk.confirm(session, card["action_id"], card["version"], card["payload_digest"])
    chain = await chat.chain(run_id)
    assert chain_problems(chain, card["action_id"]) == ["处置没有做完的验证"]
    chain["actions"][0]["remediation"]["run_id"] = "someone-else"
    assert "处置的 run_id 是 someone-else" in chain_problems(chain, card["action_id"])


async def test_with_core_service_down_the_chain_is_what_is_known_here(core, store, session, monkeypatch):
    chat = agent(core, store)
    events = await collect(chat.ask(session, HANDLE))
    run_id = of(events, "done")[0]["run_id"]

    def down(*_):
        raise CoreApiError("GET: connection refused")

    monkeypatch.setattr(chat._core, "case", down)
    chain = await chat.chain(run_id)
    assert "connection refused" in chain["core_error"] and chain["cases"] == []
    assert chain["run"]["run_id"] == run_id and len(chain["actions"]) == 1
    assert chain["actions"][0]["remediation"] is None


def test_the_chain_over_http(core, store, session):
    chat = agent(core, store)
    with TestClient(create_app(chat, store)) as client:
        assert client.get("/api/v1/chat/runs/nope").json()["code"] == "RUN_NOT_FOUND"
        with client.stream("POST", f"/api/v1/chat/sessions/{session}/messages",
                           json={"content": HANDLE, "request_id": "request-1"}) as r:
            body = "".join(r.iter_text())
        assert "run_id" in body
        run_id = client.get(f"/api/v1/chat/sessions/{session}/runs").json()["items"][0]["run_id"]
        found = client.get(f"/api/v1/chat/runs/{run_id}").json()
        assert found["run"]["request_id"] == "request-1" and found["run"]["usage"]["spans"]
        assert found["question"] == HANDLE
