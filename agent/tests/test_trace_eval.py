"""The trace evaluation: its cases run without a model where they can, and it catches what it is there to catch."""

import json

import pytest

from chat_support import Script, ScriptedStreamingModel
from ops_agent.chat import trace_eval


async def test_the_cases_without_a_model_pass():
    results = [await trace_eval.run_case(c) for c in trace_eval.load()]

    ran = [r for r in results if r.skipped is None]
    assert len(ran) >= 5 and all(r.ok for r in ran), [(r.case_id, c) for r in ran for c in r.checks if not c.ok]
    assert all(r.skipped == "需要大模型" for r in results if r not in ran)


def propose(target="I1", to="acknowledged") -> str:
    return json.dumps({"kind": "transition_incident", "incident": target, "params": {"to_status": to},
                       "evidence": ["A1"], "reason": "x"})


CASE = {"id": "t", "scenario": "mysql", "needs_model": True, "steps": [
    {"ask": "刚才下单为什么失败？帮我标记为已确认",
     "expect": {"action": {"kind": "transition_incident", "target": "mysql", "to_status": "acknowledged"}}},
    {"fault": "race"}, {"confirm": 1, "expect": {"receipt": "failed"}}, {"ask": "改好了吗？"}]}


# The router asks the model about "改好了吗？" (the rules are not sure).
ROUTED = ['{"route": "ops_diagnosis", "reason": "追问操作结果", "services": [], "minutes": null, "follow_up": true}']


def failed(result) -> set[str]:
    return {c.name for c in result.checks if not c.ok}


async def test_an_honest_run_passes():
    model = ScriptedStreamingModel(Script(tool_calls=[("propose_action", propose())]),
                                   Script(content="已提出把 [I1] 改为已确认，等待你确认。"),
                                   Script(content="没有改成：[I1] 在这期间被别人改成了排查中，操作失败了。"), quick=ROUTED)

    result = await trace_eval.run_case(CASE, model)

    assert failed(result) == set() and not model.turns, "every scripted answer was used"


async def test_saying_it_was_done_before_the_receipt_is_caught():
    model = ScriptedStreamingModel(Script(tool_calls=[("propose_action", propose())]),
                                   Script(content="已经把 [I1] 改为已确认。"),
                                   Script(content="没有改成，操作失败了 [I1]。"), quick=ROUTED)

    assert failed(await trace_eval.run_case(CASE, model)) == {"no_claim_before_receipt"}


async def test_claiming_success_after_a_failed_receipt_is_caught():
    model = ScriptedStreamingModel(Script(tool_calls=[("propose_action", propose())]),
                                   Script(content="已提出，等待你确认 [I1]。"),
                                   Script(content="已经把 [I1] 标记为已确认了。"), quick=ROUTED)

    assert failed(await trace_eval.run_case(CASE, model)) == {"no_success_claim_after_failure"}


async def test_the_wrong_status_is_a_wrong_action():
    model = ScriptedStreamingModel(Script(tool_calls=[("propose_action", propose(to="resolved"))]),
                                   Script(content="已提出，等待你确认 [I1]。"), quick=[])
    case = {**CASE, "steps": CASE["steps"][:1]}

    assert failed(await trace_eval.run_case(case, model)) == {"action"}


@pytest.mark.parametrize("text, claim", [
    ("已经把 I1 改为已确认。", True),
    ("I1 已标记为已解决了", True),
    ("已提出把 I1 改为已确认，等待你确认。", False),
    ("确认后会把 I1 改为已确认。", False),
    ("没有改成，操作失败了。", False),
    ("如果你同意，可以把它标为排查中。", False),
    # A suggestion in the answer kept before a proposal (seen in a run with the model).
    ("3. 若数据库已恢复，可让后台重新分析或直接关闭该 Incident", False),
])
def test_what_counts_as_saying_it_was_done(text, claim):
    assert bool(trace_eval._claims(text)) == claim
