import json
from datetime import datetime, timedelta, timezone
from uuid import UUID, uuid4

import pytest

from conftest import A1, E1, INCIDENT_ID, ScriptedModel, mysql_answer, submit, tool
from ops_agent.analyst import AnalysisFailed, Analyst
from ops_agent.contracts import AgentTask
from ops_agent.llm import Reply


def task(language="zh") -> AgentTask:
    now = datetime.now(timezone.utc)
    return AgentTask(task_id=uuid4(), kind="incident_analysis", incident_id=UUID(INCIDENT_ID), requested_by="system",
                     requested_at=now, deadline=now + timedelta(minutes=3), language=language)


def test_looks_at_the_incident_through_tools_and_submits_an_evidence_backed_answer(core, schemas):
    model = ScriptedModel(tool("get_incident_alerts"), tool("get_topology"),
                          mysql_answer({"kind": "alert", "id": A1, "note": "数据库不可用"}, {"kind": "event", "id": E1},
                                       {"kind": "topology", "id": "shop-mysql"}))

    result = Analyst(core, model, max_tool_calls=8).analyse(task())

    assert result.status == "completed" and not result.uncertain
    assert result.tool_calls == 2
    assert result.model == "scripted"
    assert result.hypotheses[0].component == "shop-mysql"
    assert [e.id for e in result.hypotheses[0].evidence] == [A1, E1, "shop-mysql"]
    first = model.calls[0]
    assert "简体中文" in first["messages"][0]["content"]
    assert INCIDENT_ID in first["messages"][1]["content"] and "shop-mysql" in first["messages"][1]["content"]
    assert first["tools"] == ["get_incident_alerts", "get_incident_timeline", "get_topology", "search_alerts",
                              "list_nearby_incidents", "submit_analysis"]
    alerts_result = model.calls[1]["messages"][-1]
    assert alerts_result["role"] == "tool" and A1 in alerts_result["content"]
    schemas.result(result)


def test_a_model_that_keeps_calling_tools_is_made_to_conclude(core):
    model = ScriptedModel(tool("get_incident_alerts"), tool("get_topology"), mysql_answer({"kind": "alert", "id": A1}))

    result = Analyst(core, model, max_tool_calls=2).analyse(task())

    assert result.tool_calls == 2
    assert model.calls[2]["tools"] == ["submit_analysis"]
    assert model.calls[2]["force_tool"] == "submit_analysis"


def test_tool_calls_beyond_the_limit_in_one_turn_are_refused(core):
    from ops_agent.llm import ToolCall
    many = Reply(None, [ToolCall("c1", "get_topology", "{}"), ToolCall("c2", "get_incident_alerts", "{}")])
    model = ScriptedModel(many, mysql_answer({"kind": "alert", "id": A1}))

    result = Analyst(core, model, max_tool_calls=1).analyse(task())

    assert result.tool_calls == 1
    refused = model.calls[1]["messages"][-1]
    assert refused["tool_call_id"] == "c2" and "已用完" in refused["content"]


def test_made_up_evidence_gets_one_chance_to_be_fixed(core):
    made_up = {"kind": "alert", "id": str(uuid4())}
    model = ScriptedModel(mysql_answer(made_up, {"kind": "alert", "id": A1}),
                          mysql_answer({"kind": "alert", "id": A1}))

    result = Analyst(core, model).analyse(task())

    assert "不属于本 Incident" in model.calls[1]["messages"][-1]["content"]
    assert made_up["id"] in model.calls[1]["messages"][-1]["content"]
    assert [e.id for e in result.hypotheses[0].evidence] == [A1]


def test_evidence_still_wrong_after_the_correction_is_left_to_core_service(core):
    made_up = {"kind": "topology", "id": "redis"}
    model = ScriptedModel(mysql_answer(made_up), mysql_answer(made_up))

    result = Analyst(core, model).analyse(task())

    assert [e.id for e in result.hypotheses[0].evidence] == ["redis"]


def test_an_invalid_submission_is_sent_back_with_the_errors(core):
    model = ScriptedModel(mysql_answer({"kind": "alert", "id": A1}, confidence="very high"),
                          mysql_answer({"kind": "alert", "id": A1}))

    result = Analyst(core, model).analyse(task())

    assert "不符合格式" in model.calls[1]["messages"][-1]["content"]
    assert result.hypotheses[0].confidence == "high"


def test_a_text_answer_is_asked_to_go_through_submit_analysis(core):
    model = ScriptedModel(Reply("我觉得是 MySQL 挂了"), mysql_answer({"kind": "alert", "id": A1}))

    result = Analyst(core, model).analyse(task())

    assert model.calls[1]["messages"][-1] == {"role": "user", "content": "请调用 submit_analysis 提交结论，不要直接用文字回答。"}
    assert result.status == "completed"


def test_a_model_that_never_submits_fails_the_analysis(core):
    model = ScriptedModel(*[Reply("不知道")] * 20)

    with pytest.raises(AnalysisFailed, match="没有提交结论"):
        Analyst(core, model, max_tool_calls=2).analyse(task())


def test_an_uncertain_answer_without_hypotheses_is_allowed(core):
    model = ScriptedModel(submit(summary="证据不足。", uncertain=True, hypotheses=[], next_steps=[]))

    result = Analyst(core, model).analyse(task())

    assert result.uncertain and result.hypotheses == []


def test_stops_at_the_deadline(core):
    with pytest.raises(AnalysisFailed, match="截止时间"):
        Analyst(core, ScriptedModel()).analyse(task(), deadline=datetime.now(timezone.utc) - timedelta(seconds=1))


def test_the_language_of_the_task_is_asked_for(core):
    model = ScriptedModel(mysql_answer({"kind": "alert", "id": A1}))
    Analyst(core, model).analyse(task(language="en"))
    assert "language with code en" in model.calls[0]["messages"][0]["content"]


def test_an_assistant_turn_is_sent_back_with_its_tool_calls_and_extra_fields(core):
    from ops_agent.llm import ToolCall
    thinking = Reply(None, [ToolCall("c1", "get_topology", "")], {"reasoning_content": "先看拓扑"})
    model = ScriptedModel(thinking, mysql_answer({"kind": "alert", "id": A1}))

    Analyst(core, model).analyse(task())

    assistant = model.calls[1]["messages"][-2]
    assert assistant["reasoning_content"] == "先看拓扑"
    assert assistant["tool_calls"][0]["function"] == {"name": "get_topology", "arguments": ""}
    assert json.loads(model.calls[1]["messages"][-1]["content"])["services"]
