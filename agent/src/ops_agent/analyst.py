"""The analysis loop: the model looks at an incident through the read-only tools, as many calls as it is allowed,
and concludes by calling submit_analysis with a structured, evidence-backed answer."""

import json
import logging
import time
from datetime import datetime, timezone

from pydantic import BaseModel, Field, ValidationError

from . import prompts
from .contracts import AgentResult, AgentTask, Hypothesis
from .core_api import CoreApi
from .llm import ChatModel
from .tools import Toolbox, incident_overview

log = logging.getLogger(__name__)

SUBMIT = "submit_analysis"
MAX_CORRECTIONS = 2


class Submission(BaseModel):
    summary: str = Field(min_length=1, max_length=4000)
    uncertain: bool = False
    hypotheses: list[Hypothesis] = Field(default_factory=list, max_length=5)
    next_steps: list[str] = Field(default_factory=list, max_length=10)


SUBMIT_TOOL = {"type": "function", "function": {
    "name": SUBMIT,
    "description": "提交分析结论。调用后分析结束。",
    "parameters": {
        "type": "object",
        "required": ["summary", "uncertain", "hypotheses", "next_steps"],
        "properties": {
            "summary": {"type": "string", "description": "发生了什么、影响是什么、最可能的原因，几句话"},
            "uncertain": {"type": "boolean", "description": "证据不足以下结论时为 true"},
            "hypotheses": {
                "type": "array", "maxItems": 3, "description": "可能的根因，最可能的在前",
                "items": {
                    "type": "object", "required": ["component", "cause", "confidence", "evidence"],
                    "properties": {
                        "component": {"type": "string", "description": "服务目录里的名字，如 shop-mysql"},
                        "cause": {"type": "string", "description": "这个组件出了什么问题、怎样导致了这些告警"},
                        "confidence": {"enum": ["high", "medium", "low"]},
                        "evidence": {
                            "type": "array", "maxItems": 10,
                            "items": {
                                "type": "object", "required": ["kind", "id"],
                                "properties": {
                                    "kind": {"enum": ["alert", "event", "topology", "incident"]},
                                    "id": {"type": "string", "description": "alert_id、event_id、服务名或 incident_id，原样复制"},
                                    "note": {"type": "string", "description": "这条证据说明了什么"},
                                },
                            },
                        },
                    },
                },
            },
            "next_steps": {"type": "array", "maxItems": 5, "items": {"type": "string"}},
        },
    },
}}


class AnalysisFailed(RuntimeError):
    def __init__(self, message: str, tool_calls: int = 0):
        super().__init__(message)
        self.tool_calls = tool_calls


class Analyst:
    def __init__(self, core: CoreApi, model: ChatModel, max_tool_calls: int = 8):
        self._core = core
        self._model = model
        self._max_tool_calls = max_tool_calls

    @property
    def name(self) -> str:
        return self._model.name

    def analyse(self, task: AgentTask, deadline: datetime | None = None) -> AgentResult:
        started = time.monotonic()
        incident = self._core.incident(task.incident_id)
        tools = Toolbox(self._core, incident)
        messages: list[dict] = [
            {"role": "system", "content": prompts.SYSTEM.format(language_rule=prompts.language_rule(task.language))},
            {"role": "user", "content": prompts.OVERVIEW.format(
                overview=json.dumps(incident_overview(incident), ensure_ascii=False, indent=2),
                max_tool_calls=self._max_tool_calls)},
        ]
        calls = 0
        corrections = 0
        evidence_checked = False
        for _ in range(self._max_tool_calls + 2 * MAX_CORRECTIONS + 2):
            if deadline is not None and datetime.now(timezone.utc) >= deadline:
                raise AnalysisFailed("到了截止时间，分析没有完成", calls)
            exhausted = calls >= self._max_tool_calls
            reply = self._model.complete(messages, [SUBMIT_TOOL] if exhausted else tools.specs() + [SUBMIT_TOOL],
                                         force_tool=SUBMIT if exhausted else None)
            messages.append(reply.as_message())
            if not reply.tool_calls:
                messages.append({"role": "user", "content": prompts.FORCE_SUBMIT if exhausted
                                 else prompts.NUDGE_SUBMIT})
                continue
            for call in reply.tool_calls:
                if call.name != SUBMIT:
                    if exhausted or calls >= self._max_tool_calls:
                        content = json.dumps({"error": prompts.FORCE_SUBMIT}, ensure_ascii=False)
                    else:
                        calls += 1
                        content = tools.call(call.name, call.arguments)
                        log.info("task %s tool %d/%d %s %s -> %d chars", task.task_id, calls,
                                 self._max_tool_calls, call.name, call.arguments or "{}", len(content))
                    messages.append({"role": "tool", "tool_call_id": call.id, "content": content})
                    continue
                try:
                    submission = Submission.model_validate_json(call.arguments or "{}")
                except ValidationError as e:
                    corrections += 1
                    if corrections > MAX_CORRECTIONS:
                        raise AnalysisFailed(f"提交的结论格式一直不对：{_errors(e)}", calls) from e
                    messages.append({"role": "tool", "tool_call_id": call.id,
                                     "content": prompts.INVALID_SUBMISSION.format(errors=_errors(e))})
                    continue
                unknown = [] if evidence_checked else tools.unknown_evidence(
                    [e for h in submission.hypotheses for e in h.evidence])
                if unknown:
                    # One chance to fix made-up or misquoted ids; after that core-service sets them aside.
                    evidence_checked = True
                    messages.append({"role": "tool", "tool_call_id": call.id,
                                     "content": prompts.UNKNOWN_EVIDENCE.format(evidence=", ".join(
                                         f"{e.kind}:{e.id}" for e in unknown))})
                    continue
                log.info("task %s submitted after %d tool calls: %s", task.task_id, calls,
                         ", ".join(f"{h.component} ({h.confidence})" for h in submission.hypotheses) or "no hypothesis")
                return AgentResult(task_id=task.task_id, incident_id=task.incident_id, status="completed",
                                   summary=submission.summary, uncertain=submission.uncertain,
                                   hypotheses=submission.hypotheses, next_steps=submission.next_steps,
                                   model=self._model.name, tool_calls=calls,
                                   duration_ms=int((time.monotonic() - started) * 1000))
        raise AnalysisFailed("模型没有提交结论", calls)


def _errors(e: ValidationError) -> str:
    return "; ".join(f"{'.'.join(str(p) for p in err['loc'])}: {err['msg']}" for err in e.errors()[:5])
