import asyncio
import json
import threading

import pytest
from fastapi.testclient import TestClient

from chat_support import ChatCore, ScriptedStreamingModel
from ops_agent.chat.agent import ChatAgent
from ops_agent.chat.memory import ChatStore
from ops_agent.chat.server import create_app


@pytest.fixture
def client(tmp_path):
    store = ChatStore(tmp_path / "chat.sqlite3")
    app = create_app(ChatAgent(ChatCore().api(), store, None), store)
    with TestClient(app) as c:
        yield c


def sse(text: str) -> list[tuple[str, dict]]:
    """Server-Sent Events as the browser reads them: blocks separated by a blank line, comments ignored."""
    events = []
    for block in text.replace("\r\n", "\n").split("\n\n"):
        name, data = "message", []
        for line in block.split("\n"):
            if line.startswith("event:"):
                name = line[6:].strip()
            elif line.startswith("data:"):
                data.append(line[5:].lstrip())
        if data:
            events.append((name, json.loads("\n".join(data))))
    return events


def test_a_question_is_answered_as_a_stream_of_events_and_kept(client):
    session = client.post("/api/v1/chat/sessions").json()

    with client.stream("POST", f"/api/v1/chat/sessions/{session['session_id']}/messages",
                       json={"content": "刚才下单为什么失败？", "tz": "Asia/Shanghai"}) as response:
        assert response.status_code == 200
        assert response.headers["content-type"].startswith("text/event-stream")
        assert response.headers["x-accel-buffering"] == "no"
        events = sse(response.read().decode())

    names = [e for e, _ in events]
    assert names[0] == "meta" and names[1] == "route" and names[-1] == "done"
    assert {"step", "answer", "citations"} <= set(names)

    saved = client.get(f"/api/v1/chat/sessions/{session['session_id']}").json()
    assert saved["title"] == "刚才下单为什么失败？"
    assert [m["role"] for m in saved["messages"]] == ["user", "assistant"]
    assert saved["messages"][1]["trace"]["route"]["route"] == "ops_diagnosis"
    assert "I1" in saved["refs"]
    assert client.get("/api/v1/chat/sessions").json()["items"][0]["session_id"] == session["session_id"]


def test_problems_have_codes(client):
    missing = client.post("/api/v1/chat/sessions/nope/messages", json={"content": "你好"})
    empty = client.post(f"/api/v1/chat/sessions/{client.post('/api/v1/chat/sessions').json()['session_id']}/messages",
                        json={"content": ""})

    assert missing.status_code == 404 and missing.json()["code"] == "SESSION_NOT_FOUND"
    assert missing.headers["content-type"] == "application/problem+json"
    assert empty.status_code == 422


def test_a_session_can_be_deleted(client):
    session = client.post("/api/v1/chat/sessions").json()["session_id"]

    assert client.delete(f"/api/v1/chat/sessions/{session}").status_code == 204
    assert client.get(f"/api/v1/chat/sessions/{session}").status_code == 404
    assert client.delete(f"/api/v1/chat/sessions/{session}").status_code == 404


def test_health_says_whether_a_model_is_configured(client):
    assert client.get("/api/v1/chat/health").json() == {"status": "UP", "model": None}


def test_one_question_at_a_time_per_session(tmp_path):
    started, release = threading.Event(), threading.Event()

    class Slow(ScriptedStreamingModel):
        async def quick(self, messages, *, json_output=False, timeout_s=None, on_content=None, on_usage=None):
            started.set()
            while not release.is_set():
                await asyncio.sleep(0.01)
            on_content("你好！")
            return "你好！"

    store = ChatStore(tmp_path / "chat.sqlite3")
    with TestClient(create_app(ChatAgent(ChatCore().api(), store, Slow()), store)) as client:
        session = client.post("/api/v1/chat/sessions").json()["session_id"]
        url = f"/api/v1/chat/sessions/{session}/messages"
        first: list = []
        thread = threading.Thread(target=lambda: first.append(client.post(url, json={"content": "你好"})))
        thread.start()
        assert started.wait(5)

        busy = client.post(url, json={"content": "你好"})
        release.set()
        thread.join(5)

        assert busy.status_code == 409 and busy.json()["code"] == "SESSION_BUSY"
        assert sse(first[0].text)[-1][1]["status"] == "completed"
        assert client.post(url, json={"content": "谢谢"}).status_code == 200


def test_a_questionnaire_is_answered_through_the_messages_endpoint(tmp_path):
    from chat_support import PAYMENT_INCIDENT

    core = ChatCore()
    core.add_payment_incident()
    store = ChatStore(tmp_path / "chat.sqlite3")
    with TestClient(create_app(ChatAgent(core.api(), store, None), store)) as client:
        session = client.post("/api/v1/chat/sessions").json()["session_id"]
        url = f"/api/v1/chat/sessions/{session}/messages"
        with client.stream("POST", url, json={"content": "刚才为什么报错？"}) as r:
            [q] = [d for e, d in sse(r.read().decode()) if e == "questionnaire"]
        assert client.get(f"/api/v1/chat/sessions/{session}").json()["pause"]["pause_id"] == q["pause_id"]
        payment = next(o["id"] for o in q["questions"][0]["options"] if o.get("incident_id") == PAYMENT_INCIDENT)

        wrong = client.post(url, json={"reply": {"pause_id": q["pause_id"], "answers": [{"id": "target",
                                                                                         "choices": ["I9"]}]}})
        reply = {"reply": {"pause_id": q["pause_id"], "answers": [{"id": "target", "choices": [payment]}]}}
        with client.stream("POST", url, json=reply) as r:
            events = sse(r.read().decode())
        twice = client.post(url, json=reply)
        saved = client.get(f"/api/v1/chat/sessions/{session}").json()

    assert wrong.status_code == 400 and wrong.json()["code"] == "VALIDATION_FAILED"
    assert [d for e, d in events if e == "route"][0]["resumed"] is True
    assert twice.status_code == 409 and twice.json()["code"] == "PAUSE_CLOSED"
    assert saved["pause"] is None and "payment-service answered with 503" in saved["messages"][2]["content"]


def test_an_action_is_confirmed_through_the_api_once(tmp_path):
    from chat_support import DB_ALERT, MYSQL_INCIDENT
    from ops_agent.chat.evidence import RefTable

    core = ChatCore()
    store = ChatStore(tmp_path / "chat.sqlite3")
    agent = ChatAgent(core.api(), store, None, writer=core.writer())
    session = store.create_session()["session_id"]
    refs = RefTable()
    refs.ref("incident", MYSQL_INCIDENT, "mysql")
    refs.ref("alert", DB_ALERT, "db", MYSQL_INCIDENT)
    store.update_session(session, refs=refs.as_json())
    card = asyncio.run(agent.desk.propose(session, "m1", refs, {
        "kind": "transition_incident", "incident": "I1", "params": {"to_status": "acknowledged"},
        "evidence": ["A1"], "reason": "MySQL 停机"}))
    base = f"/api/v1/chat/sessions/{session}/actions/{card['action_id']}"
    with TestClient(create_app(agent, store)) as client:
        stale = client.post(base + "/confirm", json={"version": 2})
        ok = client.post(base + "/confirm", json={"version": 1})
        again = client.post(base + "/confirm", json={"version": 1})
        late = client.post(base + "/decline", json={"version": 1})
        missing = client.post(f"/api/v1/chat/sessions/{session}/actions/nope/confirm", json={"version": 1})
        listed = client.get(f"/api/v1/chat/sessions/{session}").json()["actions"]

    assert stale.status_code == 409 and stale.json()["code"] == "ACTION_CHANGED"
    assert stale.json()["action"]["status"] == "proposed", "the conflict shows the card as it is"
    assert ok.status_code == 200 and ok.json()["status"] == "succeeded"
    assert again.json()["receipt"]["receipt_id"] == ok.json()["receipt"]["receipt_id"] and len(core.writes) == 1
    assert late.status_code == 409 and missing.status_code == 404
    assert listed[0]["status"] == "succeeded"
