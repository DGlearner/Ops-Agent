"""Behavior at the chat/background boundary, using the real HTTP clients and graph."""

import copy
import json
from datetime import datetime, timedelta, timezone

import httpx
import pytest

from chat_support import (MYSQL_INCIDENT, OLD_INCIDENT, ChatCore, Script, ScriptedStreamingModel, answer,
                          collect, iso, of)
from ops_agent.chat.agent import ChatAgent
from ops_agent.chat.diagnostics import DiagnosisDesk, applicable, latest
from ops_agent.chat.evidence import RefTable
from ops_agent.chat.memory import ChatStore
from ops_agent.chat.tools import ChatTools
from zoneinfo import ZoneInfo


class DiagnosticCore(ChatCore):
    def __init__(self):
        super().__init__()
        self.analyses = {}
        self.ensure_calls = 0
        self.reply_fault = None

    def handle(self, request):
        if request.method == "POST" and request.url.path.endswith("/analyses/ensure"):
            self.requests.append(request)
            self.ensure_calls += 1
            incident_id = request.url.path.split("/")[4]
            if self.reply_fault == "lost":
                raise httpx.ReadTimeout("lost acknowledgement", request=request)
            if self.reply_fault == "mismatch":
                return httpx.Response(202, json={"analysis_id": "wrong", "incident_id": OLD_INCIDENT,
                                                 "status": "pending"})
            rows = self.analyses.setdefault(incident_id, [])
            if not rows:
                rows.append({"analysis_id": "task-1", "incident_id": incident_id, "status": "pending",
                             "requested_at": iso(datetime.now(timezone.utc)), "requested_by": "system"})
            return httpx.Response(202, json=copy.deepcopy(rows[0]))
        return super().handle(request)


@pytest.fixture
def core():
    return DiagnosticCore()


@pytest.fixture
def store(tmp_path):
    db = ChatStore(tmp_path / "chat.sqlite3")
    yield db
    db.close()


def delegate(reason="已有工具查询不足以完成复杂调查"):
    return Script(tool_calls=[("delegate_diagnosis", json.dumps({"incident": "I1", "reason": reason}))])


async def test_complex_investigation_returns_pending_without_waiting_and_survives_reload(core, store):
    model = ScriptedStreamingModel(delegate(), Script(content="后台诊断等待或执行中；目前仅能确定调用失败 [I1]。"))
    session = store.create_session()["session_id"]
    events = await collect(ChatAgent(core.api(), store, model, writer=core.writer()).ask(
        session, "刚才下单为什么失败？"))
    [task] = of(events, "analysis")
    assert task["analysis_id"] == "task-1" and task["status"] == "pending" and not task["stale"]
    assert [e for e, _ in events].index("analysis") < [e for e, _ in events].index("answer")
    assert of(events, "done")[0]["status"] == "completed"  # Worker is still pending when chat has finished.
    assert core.analyses[MYSQL_INCIDENT][0]["status"] == "pending"
    assert core.ensure_calls == 1 and core.writes == []
    assert store.messages(session)[-1]["trace"]["analyses"] == [task]


async def test_repeated_model_turns_and_followups_reuse_the_task(core, store):
    model = ScriptedStreamingModel(delegate(), delegate(), Script(content="任务正在执行 [I1]。"),
                                   delegate(), Script(content="仍等待同一任务 [I1]。"))
    session = store.create_session()["session_id"]
    agent = ChatAgent(core.api(), store, model, writer=core.writer())
    first = await collect(agent.ask(session, "刚才下单为什么失败？"))
    second = await collect(agent.ask(session, "刚才下单为什么失败？"))
    assert {t["analysis_id"] for t in of(first + second, "analysis")} == {"task-1"}
    assert core.ensure_calls == 1


@pytest.mark.parametrize("status", ["completed", "failed", "timed_out"])
async def test_terminal_attempt_is_not_automatically_retried(core, store, status):
    core.analyses[MYSQL_INCIDENT] = [{"analysis_id": "done-1", "incident_id": MYSQL_INCIDENT, "status": status,
                                    "requested_at": iso(datetime.now(timezone.utc)), "summary": "证据支持数据库不可用",
                                    "model": "test", "hypotheses": []}]
    model = ScriptedStreamingModel(delegate(), Script(content="按当前证据说明 [I1]。"))
    session = store.create_session()["session_id"]
    events = await collect(ChatAgent(core.api(), store, model, writer=core.writer()).ask(
        session, "刚才下单为什么失败？"))
    assert core.ensure_calls == 0
    assert of(events, "analysis")[-1]["status"] == status
    assert "不会自动重复分析" in model.calls[1]["messages"][-1]["content"]


async def test_use_existing_result_and_readonly_lookup_without_dispatch(core, store):
    core.analyses = ChatCore().analyses
    model = ScriptedStreamingModel(Script(tool_calls=[("search_logs", '{"service":"order-service"}')]),
                                   Script(content="结合当前日志和后台判断 [I1, A1]。"))
    events = await collect(ChatAgent(core.api(), store, model, writer=core.writer()).ask(
        store.create_session()["session_id"], "刚才下单为什么失败？"))
    assert "background_analysis" in model.calls[0]["messages"][-1]["content"]
    assert core.ensure_calls == 0 and core.writes == []
    assert of(events, "analysis")[0]["status"] == "completed"


@pytest.mark.parametrize("empty", ["incident", "alerts", "samples"])
async def test_missing_evidence_never_blindly_schedules_a_diagnosis(core, store, empty):
    if empty == "incident":
        core.incidents = {}
        core.alerts = {}
    elif empty == "alerts":
        core.alerts[MYSQL_INCIDENT] = []
    else:
        for alert in core.alerts[MYSQL_INCIDENT]:
            alert["samples"] = []
    core.log_lines = []
    events = await collect(ChatAgent(core.api(), store, None, writer=core.writer()).ask(
        store.create_session()["session_id"], "刚才下单为什么失败？"))
    assert core.ensure_calls == 0
    assert "证据不足" in answer(events)


async def test_explicit_reanalysis_still_requires_the_existing_confirmation_card(core, store):
    model = ScriptedStreamingModel(Script(tool_calls=[("propose_action", json.dumps({
        "kind": "request_analysis", "incident": "I1", "evidence": ["A1"], "reason": "用户请求重新分析"}))]),
        Script(content="已提出重新分析，等待确认 [I1]。"))
    session = store.create_session()["session_id"]
    agent = ChatAgent(core.api(), store, model, writer=core.writer())
    events = await collect(agent.ask(session, "刚才下单为什么失败？重新分析一下"))
    assert core.ensure_calls == 0 and core.writes == []
    assert "delegate_diagnosis" not in model.calls[0]["tools"]
    [card] = of(events, "action")
    assert card["status"] == "proposed" and card["kind"] == "request_analysis"
    await agent.desk.confirm(session, card["action_id"], card["version"])
    assert len(core.writes) == 1 and core.writes[0][0].endswith("/analyses")


async def test_old_completion_or_wrong_incident_cannot_become_current_analysis(core):
    incident = core.incidents[MYSQL_INCIDENT]
    now = datetime.now(timezone.utc)
    core.analyses[MYSQL_INCIDENT] = [
        {"analysis_id": "new", "incident_id": MYSQL_INCIDENT, "status": "pending", "requested_at": iso(now)},
        {"analysis_id": "old", "incident_id": MYSQL_INCIDENT, "status": "completed", "summary": "旧根因",
         "model": "x", "requested_at": iso(now - timedelta(minutes=10)), "completed_at": iso(now)},
        {"analysis_id": "wrong", "incident_id": OLD_INCIDENT, "status": "completed", "summary": "别的事件",
         "requested_at": iso(now + timedelta(minutes=1))}]
    tools = ChatTools(core.api(), RefTable(), now, ZoneInfo("UTC"))
    raw = await tools.fetch_detail(incident)
    shaped = tools.shape_detail(incident, raw)
    assert shaped["background_task"]["analysis_id"] == "new" and "background_analysis" not in shaped
    assert latest(incident, raw)["analysis_id"] == "new"
    raw["timeline"] = [{"type": "ALERT_ATTACHED", "at": iso(now + timedelta(seconds=1))}]
    assert not applicable(incident, raw, latest(incident, raw))  # late arrival, same old event time


@pytest.mark.parametrize("fault", ["lost", "mismatch"])
async def test_uncertain_or_mismatched_receipt_is_not_retried_by_model_loop(core, fault):
    core.reply_fault = fault
    desk = DiagnosisDesk(core.writer())
    tools = ChatTools(core.api(), RefTable(), datetime.now(timezone.utc), ZoneInfo("UTC"))
    incident = core.incidents[MYSQL_INCIDENT]
    desk.observe("I1", incident, await tools.fetch_detail(incident))
    first = await desk.delegate("I1", "复杂调查")
    second = await desk.delegate("I1", "复杂调查")
    assert first == second and first[0] is None and core.ensure_calls == 1


async def test_unread_or_wrong_scope_cannot_be_delegated(core):
    with pytest.raises(ValueError):
        await DiagnosisDesk(core.writer()).delegate("I99", "复杂调查")
    assert core.ensure_calls == 0
