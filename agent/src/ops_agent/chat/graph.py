"""The orchestration, as a LangGraph state graph:

    route ─┬─ chitchat / out_of_scope ──────────────────────────▶ smalltalk
           ├─ ops_knowledge ────────────────────────────────────▶ knowledge
           └─ ops_diagnosis / ops_query ─▶ locate ─┬─▶ gather ─▶ reason ─▶ verify
                                    (resume) ──▶ ┘ └─▶ (pause: a questionnaire)

For an ops question the evidence is fetched before the model is asked anything: locate finds the incidents the
question is about (an id in it, the incidents the conversation is about, or the ones with alerts in the time window,
widened to a day when the window is empty), gather fetches their alerts, samples, rule attribution and background
analysis in parallel. The model then reasons over that pack and may look further with up to chat_max_tool_calls tool
calls; verify checks every ref it cited. Without a model, or when it fails, the answer is written from the pack.

Pause points (chat.clarify): locate may end the answer with a questionnaire when the question could be about several
unrelated incidents, and the model may with ask_user. The next message goes on from there: the graph starts at locate
with the route already decided and the incidents the user picked, fetched again. Besides the read-only tools the
model may propose an action (chat.actions) or a memory (chat.knowledge): both become cards for a person to decide on,
nothing is changed here.

Every node reports what it does through the stream writer, and those events go to the browser as they happen: route,
step (running, then done or error, with timings), thinking (the model's reasoning), answer, interim (text the model
wrote before deciding to call a tool: a note, not the answer), action and memory (cards), citations.
"""

import asyncio
import json
import logging
import time
from collections.abc import AsyncIterator, Awaitable, Callable
from contextlib import asynccontextmanager
from dataclasses import dataclass, field
from datetime import datetime
from typing import Any, TypedDict
from zoneinfo import ZoneInfo

from langgraph.graph import END, START, StateGraph
from langgraph.runtime import Runtime

import re

from ..baseline import rule_attribution
from ..core_api import CoreApi, CoreApiError, parse_time
from . import clarify, prompts
from . import gaps as gap_rules
from . import runbooks
from .casework import CaseDesk, CaseError
from . import casework
from .claims import claimed
from . import actions as action_cards
from . import knowledge as knowledge_cards
from .evidence import citations
from .llm import StreamingModel
from .router import OPS_ROUTES, Decision, Router
from .tools import MAX_ALERTS, ChatTools, ToolResult, _involved
from .usage import STEP, Ledger

log = logging.getLogger(__name__)

DEFAULT_MINUTES = 60
WIDENED_MINUTES = 24 * 60
DETAILED = 3
STATUS_LABEL = {"open": "未处理", "acknowledged": "已确认", "investigating": "排查中", "mitigated": "已缓解",
                "resolved": "已解决", "closed": "已关闭"}
SEVERITY_LABEL = {"critical": "严重", "high": "高", "medium": "中", "low": "低", "info": "提示"}
TOOL_TITLE = {"list_incidents": "查询 Incident", "get_incident": "查看 Incident 详情", "search_alerts": "查询告警",
              "search_logs": "查询日志", "count_logs": "统计日志", "get_topology": "查看服务依赖",
              "propose_action": "提出操作", "propose_memory": "记为候选经验", "ask_user": "向用户确认",
              "update_case": "记到处置单"}
# Tools that only make a card for a person to decide on, or note something on the case: they look nothing up.
CARD_TOOLS = {"propose_action", "propose_memory", "update_case"}
# The user asks for the incident to be handled, not only explained: without a model, a ready runbook remediation is
# then proposed as a card (the outcome evaluation's baseline, docs/agent-design.md §9.4).
HANDLE = re.compile(r"处理|修复|修好|恢复|解决|止血|怎么办|handle|fix|mitigate", re.IGNORECASE)
DETERMINATE = ("picked", "named", "focus", "page")
ASK_USER = {"type": "function", "function": {
    "name": "ask_user",
    "description": "向用户提 1–4 个问题，等用户回答后再继续。只问工具查不到、又影响结论的事（用户看到了什么现象、"
                   "做过什么操作）；能查的先查。调用后这次回答就结束了。",
    "parameters": {"type": "object", "additionalProperties": False, "required": ["questions"], "properties": {
        "questions": {"type": "array", "minItems": 1, "maxItems": clarify.MAX_QUESTIONS, "items": {
            "type": "object", "additionalProperties": False, "required": ["title"], "properties": {
                "id": {"type": "string"}, "title": {"type": "string"},
                "options": {"type": "array", "items": {"type": "string"}, "maxItems": clarify.MAX_OPTIONS},
                "multiple": {"type": "boolean"}, "allow_text": {"type": "boolean"}}}}}}}}


@dataclass
class Run:
    """One question: what the nodes need besides the state."""
    question: str
    now: datetime
    tz: ZoneInfo
    memory: dict
    tools: ChatTools
    catalog: set[str]
    router: Router
    model: StreamingModel | None
    max_tool_calls: int
    # Off: the model starts from the scope alone and fetches everything with its tools (the experiment's baseline).
    prefetch: bool = True
    # The user's choice for this question ("深度思考"); None: the configured default.
    thinking: bool | None = None
    # The session's tools, given by the agent; None leaves a tool out.
    propose: Callable[[dict], Awaitable[dict]] | None = None
    remember: Callable[[dict], dict] | None = None
    known: Callable[[set[str]], list[dict]] | None = None
    # May end the answer with a questionnaire: not when going on from one.
    may_ask: bool = True
    # Going on from a pause point: incident_ids, the incidents the user picked (None: as the question says).
    resume: dict | None = None
    # The text before this turn's tool calls is kept as the answer (they only made cards); the next text follows on.
    kept: bool = False
    # Cards proposed in this answer.
    proposed: int = 0
    # The case, the runbook and its probes (docs/agent-design.md §13.4): None leaves them out.
    cases: CaseDesk | None = None
    books: list = field(default_factory=list)
    core: CoreApi | None = None
    update_case: Callable[[dict], Awaitable[dict]] | None = None
    # The page the question was asked from: incident_id, case_id, case_version.
    page: dict | None = None
    # What this answer worked out for the case: case, match, observations, options, gaps, forbidden.
    work: dict = field(default_factory=dict)
    # What this run spent (chat.usage).
    ledger: Ledger = field(default_factory=Ledger)
    steps: int = 0
    answered: list[str] = field(default_factory=list)


class ChatState(TypedDict, total=False):
    decision: Decision
    incidents: list[dict]
    scope: dict
    pack: dict
    answer: str
    tool_calls: int
    degraded: bool
    focus: dict
    # The answer ends with these questions; the next message goes on from here.
    pause: dict
    known: list[dict]


class Step:
    def __init__(self) -> None:
        self.result = ""
        self.ok = True
        # The refs of what the step settled on (locate: the incidents), for the trace and its evaluation.
        self.refs: list[str] = []


def emit(runtime: Runtime[Run], event: str, data: dict) -> None:
    runtime.stream_writer({"event": event, "data": data})


@asynccontextmanager
async def step(runtime: Runtime[Run], kind: str, title: str, detail: str | None = None) -> AsyncIterator[Step]:
    runtime.context.steps += 1
    step_id = f"s{runtime.context.steps}"
    base = {"id": step_id, "kind": kind, "title": title, **({"detail": detail} if detail else {})}
    emit(runtime, "step", {**base, "status": "running"})
    started = time.monotonic()
    current = Step()
    span = runtime.context.ledger.begin(STEP, title, step_kind=kind)
    try:
        yield current
    except Exception as e:
        runtime.context.ledger.end(span, "error")
        emit(runtime, "step", {**base, "status": "error", "result": current.result or _error_text(e),
                               "duration_ms": _ms(started)})
        raise
    runtime.context.ledger.end(span, "ok" if current.ok else "error")
    emit(runtime, "step", {**base, "status": "done" if current.ok else "error", "result": current.result,
                           **({"refs": current.refs} if current.refs else {}), "duration_ms": _ms(started)})


def say(runtime: Runtime[Run], text: str) -> None:
    runtime.context.answered.append(text)
    emit(runtime, "answer", {"delta": text})


async def route(state: ChatState, runtime: Runtime[Run]) -> dict:
    run = runtime.context
    decision = await run.router.decide(run.question, now=run.now, catalog=run.catalog, focus=run.memory["focus"],
                                       history=run.memory["history"])
    emit(runtime, "route", decision.as_dict())
    return {"decision": decision}


def after_route(state: ChatState) -> str:
    r = state["decision"].route
    return "locate" if r in OPS_ROUTES else "knowledge" if r == "ops_knowledge" else "smalltalk"


async def smalltalk(state: ChatState, runtime: Runtime[Run]) -> dict:
    run = runtime.context
    if state["decision"].route == "out_of_scope":
        say(runtime, prompts.OUT_OF_SCOPE)
    elif run.model is None:
        say(runtime, prompts.CAPABILITIES)
    else:
        try:
            await run.model.quick([{"role": "system", "content": prompts.CHITCHAT}, *run.memory["history"],
                                   {"role": "user", "content": run.question}], on_content=lambda d: say(runtime, d))
        except Exception as e:  # noqa: BLE001 - a greeting does not need the model
            log.warning("chitchat: the model failed (%s), answering with the capabilities", e)
            if not run.answered:
                say(runtime, prompts.CAPABILITIES)
    return {"answer": "".join(run.answered)}


async def knowledge(state: ChatState, runtime: Runtime[Run]) -> dict:
    run = runtime.context
    if run.model is None:
        say(runtime, prompts.NO_MODEL_KNOWLEDGE)
        return {"answer": prompts.NO_MODEL_KNOWLEDGE}
    system = prompts.KNOWLEDGE.format(context=prompts.context_block(run.memory["focus"], run.memory["summary"]))
    turn = await run.model.stream([{"role": "system", "content": system}, *run.memory["history"],
                                   {"role": "user", "content": run.question}],
                                  on_reasoning=lambda d: emit(runtime, "thinking", {"delta": d}),
                                  on_content=lambda d: say(runtime, d), thinking=run.thinking)
    return {"answer": turn.content}


async def locate(state: ChatState, runtime: Runtime[Run]) -> dict:
    run = runtime.context
    found = state["decision"].entities
    focus = run.memory["focus"].get("incidents") or []
    picked = (run.resume or {}).get("incident_ids")
    async with step(runtime, "locate", "定位相关的 Incident") as s:
        widened = False
        minutes = found.minutes or DEFAULT_MINUTES
        if picked:
            kind, how = "picked", "你选择的 Incident"
            incidents = await _fetch(run.tools, tuple(picked[:DETAILED]))
        elif found.incident_ids:
            kind, how = "named", "问题里给出的 Incident"
            incidents = await _fetch(run.tools, found.incident_ids[:DETAILED])
        elif (on_page := await _page_incident(run, found)) is not None:
            kind, how = "page", "当前页面上的 Incident"
            incidents = [on_page]
        elif found.follow_up and focus and not found.services and found.minutes is None:
            kind, how = "focus", "上文讨论的 Incident"
            incidents = await _fetch(run.tools, tuple(i["incident_id"] for i in focus[:DETAILED]))
        else:
            kind = "window"
            incidents = await run.tools.recent_incidents(minutes, found.services)
            if not incidents and found.minutes is None:
                minutes, widened = WIDENED_MINUTES, True
                incidents = await run.tools.recent_incidents(minutes, found.services)
            how = f"最近 {_span(minutes)}有告警" + (f"、涉及 {'、'.join(found.services)}" if found.services else "")
        s.refs = [run.tools.brief(i)["ref"] for i in incidents[:DETAILED + 7]]
        s.result = f"{how}：{len(incidents)} 个" + (f"（最近 {_span(DEFAULT_MINUTES)}没有，扩大到 {_span(minutes)}）"
                                                   if widened else "")
    scope = {"kind": kind, "how": how, "minutes": minutes, "widened": widened, "components": list(found.services)}
    if run.may_ask and kind == "window":
        asked = clarify.target_questionnaire(run.question, state["decision"], incidents,
                                             lambda i: run.tools.brief(i)["ref"], f"最近 {_span(minutes)}")
        if asked:
            say(runtime, asked["questions"][0]["title"].replace("，你问的是哪个？", "") + "，先确认你问的是哪个"
                "（也可以选“都看看”）。")
            return {"incidents": incidents, "scope": scope, "pause": asked, "answer": "".join(run.answered)}
    return {"incidents": incidents, "scope": scope}


def after_locate(state: ChatState) -> str:
    return END if state.get("pause") else "gather"


async def gather(state: ChatState, runtime: Runtime[Run]) -> dict:
    run = runtime.context
    incidents = state["incidents"]
    scope = state["scope"]
    pack: dict[str, Any] = {"scope": {"how": scope["how"], "window": f"最近 {_span(scope['minutes'])}",
                                      **({"components": scope["components"]} if scope["components"] else {})}}
    if not run.prefetch:
        pack["incidents"] = [{"ref": run.tools.brief(i)["ref"], "title": i["title"]} for i in incidents]
        pack["note"] = "只列出了找到的 Incident，详情、告警和规则归因需要用工具查"
        return {"pack": pack}
    # The first few in full either way: a status question ("是同一个原因吗") needs the samples as much as a
    # diagnosis; without them the model guesses or spends tool calls on fetching them.

    async def detail(incident: dict) -> dict:
        ref = run.tools.brief(incident)["ref"]
        async with step(runtime, "fetch", f"读取 {ref} 的告警、相关服务的日志、规则归因和后台分析",
                        incident["title"]) as s:
            raw = await run.tools.fetch_detail(incident)
            pointed = [h.component for h in rule_attribution(incident, raw["alerts"])]
            s.result = f"{min(len(raw['alerts']), MAX_ALERTS)} 条告警，规则指向 " + ("、".join(pointed) or "不明确") + (
                "；日志：" + "、".join(f"{svc} {sum(c['count'] for c in got['events'])} 行"
                                      f"（警告和错误 {len(got['errors'])} 条）" for svc, got in raw["logs"].items())
                if raw["logs"] else "")
            return raw

    async def alerts() -> list[dict]:
        async with step(runtime, "fetch", "查询最近的告警") as s:
            result = await run.tools.recent_alerts(scope["minutes"], scope["components"][0]
                                                   if len(scope["components"]) == 1 else None)
            s.result = f"{len(result)} 条"
            return result

    status = state["decision"].route == "ops_query" and scope["kind"] == "window"
    fetched = await asyncio.gather(*(detail(i) for i in incidents[:DETAILED]),
                                   *([alerts()] if status or not incidents else []))
    # Refs in the incidents' order, whichever fetch finished first.
    pack["incidents"] = [run.tools.shape_detail(i, raw) for i, raw in zip(incidents[:DETAILED], fetched)]
    if len(incidents) > DETAILED:
        others = []
        for incident in incidents[DETAILED:DETAILED + 7]:
            brief = run.tools.brief(incident)
            brief["rule_attribution"] = _pointed_at(incident)
            others.append(brief)
        pack["other_incidents"] = others
    if status or not incidents:
        pack["recent_alerts"] = fetched[-1]
    if len(incidents) > 1:
        pack["incident_timing"] = _timing(run.tools, incidents[:DETAILED + 7])
    if incidents and state["decision"].route == "ops_diagnosis" and (run.cases or run.books):
        await _casework(runtime, state, incidents[:DETAILED], fetched[:len(incidents[:DETAILED])], pack)
    known: list[dict] = []
    if run.known:
        components = set(scope["components"]).union(*(_involved(i) for i in incidents[:DETAILED]))
        known = run.known(components)
        if known:
            async with step(runtime, "recall", "读取已审核的运维经验") as s:
                s.result = f"{len(known)} 条：" + "；".join(m["text"][:30] for m in known)
    return {"pack": pack, "known": known}


async def reason(state: ChatState, runtime: Runtime[Run]) -> dict:
    run = runtime.context
    diagnosis = state["decision"].route == "ops_diagnosis"
    if run.model is None:
        say(runtime, written_answer(state["pack"], diagnosis, degraded=False))
        if diagnosis and HANDLE.search(run.question):
            await _runbook_card(runtime, state["pack"])
        return {"answer": "".join(run.answered), "tool_calls": 0, "degraded": False}
    messages = prompts.ops_messages(run.question, state["pack"], diagnosis=diagnosis, now=run.now, tz=str(run.tz),
                                    focus=run.memory["focus"], summary=run.memory["summary"],
                                    history=run.memory["history"], max_tool_calls=run.max_tool_calls,
                                    actions=run.memory.get("actions") or [], known=state.get("known") or [],
                                    can_propose=run.propose is not None)
    session_specs = ([action_cards.tool_spec()] if run.propose else []) + (
        [knowledge_cards.tool_spec()] if run.remember else []) + ([ASK_USER] if run.may_ask else []) + (
        [casework.tool_spec()] if run.update_case and run.work.get("case") else [])
    calls = 0

    def go_on(delta: str) -> None:
        if run.kept and run.answered:
            say(runtime, "\n\n")
        run.kept = False
        say(runtime, delta)

    try:
        for _ in range(run.max_tool_calls + 2):
            exhausted = calls >= run.max_tool_calls
            turn = await run.model.stream(messages, None if exhausted else run.tools.specs() + session_specs,
                                          on_reasoning=lambda d: emit(runtime, "thinking", {"delta": d}),
                                          on_content=go_on, thinking=run.thinking)
            if not turn.tool_calls:
                return {"answer": "".join(run.answered), "tool_calls": calls, "degraded": False}
            run.kept = bool(run.answered) and all(c.name in CARD_TOOLS for c in turn.tool_calls)
            if run.answered and not run.kept:
                # Written before the model decided to look further: a note on the way, not the answer. Before a
                # card (an action or a memory proposed) it is the answer itself: the card adds to it.
                emit(runtime, "interim", {"text": "".join(run.answered)})
                run.answered.clear()
            messages.append(turn.as_message())
            asked = next((c for c in turn.tool_calls if c.name == "ask_user" and run.may_ask), None)
            if asked:
                questionnaire = _ask_user(runtime, asked.arguments)
                if questionnaire:
                    if not run.answered:
                        say(runtime, "需要先确认几件事：")
                    return {"answer": "".join(run.answered), "tool_calls": calls, "degraded": False,
                            "pause": questionnaire}
            for call in turn.tool_calls:
                if call.name == "ask_user":
                    messages.append({"role": "tool", "tool_call_id": call.id,
                                     "content": '{"error": "这次不能再问用户，请根据已有的信息回答"}'})
                    continue
                if calls >= run.max_tool_calls:
                    messages.append({"role": "tool", "tool_call_id": call.id,
                                     "content": '{"error": "' + prompts.FINAL_ANSWER + '"}'})
                    continue
                calls += 1
                async with step(runtime, "tool", TOOL_TITLE.get(call.name, call.name),
                                _arguments(call.arguments)) as s:
                    result = await _call(runtime, call.name, call.arguments)
                    s.result, s.ok = result.summary, result.ok
                messages.append({"role": "tool", "tool_call_id": call.id, "content": result.content})
            if calls >= run.max_tool_calls:
                messages.append({"role": "user", "content": prompts.FINAL_ANSWER})
        raise RuntimeError("模型一直在调用工具，没有给出回答")
    except Exception as e:  # noqa: BLE001 - the rules' answer is still worth giving
        log.warning("chat: the model failed (%s: %s); answering from the fetched data", type(e).__name__, e)
        async with step(runtime, "fallback", "模型调用失败，改为根据查到的数据回答") as s:
            s.result = _error_text(e)
        if run.answered:
            emit(runtime, "interim", {"text": "".join(run.answered)})
            run.answered.clear()
        say(runtime, written_answer(state["pack"], diagnosis, degraded=True))
        return {"answer": "".join(run.answered), "tool_calls": calls, "degraded": True}


async def verify(state: ChatState, runtime: Runtime[Run]) -> dict:
    run = runtime.context
    if run.proposed and claimed("".join(run.answered)):
        # The model said it was done; the card is still waiting. The user is told so, in so many words.
        say(runtime, "\n\n> 注意：上面的操作还没有执行，卡片在等待你确认；做没做成以确认后的回执为准。")
    answer = "".join(run.answered) or state.get("answer") or ""
    async with step(runtime, "check", "核对引用") as s:
        cites = citations(answer, run.tools.refs)
        unknown = [c["ref"] for c in cites if not c["verified"]]
        s.result = f"{len(cites)} 处引用" + (f"，{'、'.join(unknown)} 不在查到的数据里" if unknown else "，都有出处")
        s.ok = not unknown
    emit(runtime, "citations", {"items": cites})
    return {"focus": _focus(run, state, cites)}


async def _call(runtime: Runtime[Run], name: str, arguments: str) -> ToolResult:
    """A tool call: the session's own tools here, the read-only data tools in ChatTools."""
    run = runtime.context
    if name == "propose_action" and run.propose:
        try:
            card = await run.propose(_args(arguments))
        except (action_cards.ProposalError, ValueError) as e:
            return _tool_error(f"操作没有提出：{e}")
        emit(runtime, "action", card)
        run.proposed += 1
        next_text = ("你在调用前写的回答已经原样给了用户，接着只需补一句：这个操作已提出，等待确认" if run.kept else
                     "用户问的其他问题（原因、影响）照常按格式回答，带引用，最后说明这个操作已提出、等待确认")
        return ToolResult(json.dumps({"action_id": card["action_id"], "title": card["title"], "status": card["status"],
                                      "note": "卡片已发给用户，用户确认后系统才会执行。不要说已经执行或已经改好。"
                                              + next_text}, ensure_ascii=False),
                          f"{card['title']}：等待用户确认")
    if name == "update_case" and run.update_case:
        try:
            noted = await run.update_case(_args(arguments))
        except (CaseError, ValueError) as e:
            return _tool_error(f"没有记下：{e}")
        emit(runtime, "case", {"case_id": run.work["case"]["case_id"], **noted})
        return ToolResult(json.dumps({**noted, "note": "已记到处置单。你在调用前写的回答已保留，接着写完即可"
                                      if run.kept else "已记到处置单"}, ensure_ascii=False),
                          f"{noted.get('entry')}：已记下")
    if name == "propose_memory" and run.remember:
        try:
            memory = run.remember(_args(arguments))
        except ValueError as e:
            return _tool_error(f"没有记下：{e}")
        emit(runtime, "memory", memory)
        return ToolResult(json.dumps({"memory_id": memory["memory_id"], "status": memory["status"],
                                      "note": "已记为候选，人工审核通过后才会在之后的对话里使用"}, ensure_ascii=False),
                          "已记为候选，等待审核" + ("（引用了日志，审核时需注意）" if memory["flags"] else ""))
    return await run.tools.call(name, arguments)


async def _casework(runtime: Runtime[Run], state: ChatState, incidents: list[dict], fetched: list[dict],
                    pack: dict) -> None:
    """The case this diagnosis works on, the runbook that fits the first incident, its probes and what is still
    missing (gaps). Each part is a step the user sees; a failure leaves the part out, the answer goes on."""
    run = runtime.context
    scope = state["scope"]
    pointed = [{h.component for h in rule_attribution(i, raw["alerts"])} for i, raw in zip(incidents, fetched)]
    determinate = scope["kind"] in DETERMINATE or len({frozenset(p) for p in pointed if p}) <= 1
    primary, raw = incidents[0], fetched[0]
    ref = run.tools.brief(primary)["ref"]
    case = None
    if determinate and run.cases:
        async with step(runtime, "case", f"打开 {ref} 的处置单") as s:
            try:
                case = await run.cases.open([primary["incident_id"]], primary["title"])
            except CoreApiError as e:
                s.ok, s.result = False, f"打不开处置单：{e}"[:200]
            else:
                s.result = (f"处置单状态：{case['status']}（第 {case['state_version']} 版）" if case
                            else "没有处置单（没有开启写操作）")
    matched, observations = None, []
    if determinate and run.books:
        matched = runbooks.match(run.books, sorted(pointed[0]), _texts(raw))
        if matched:
            observations = await _probe(runtime, matched.runbook, primary["incident_id"])
            handed = runbooks.deferred(matched.runbook, observations, run.books)
            if handed:
                book, why = handed
                observations += await _probe(runtime, book, primary["incident_id"],
                                             f"{why}：从「{matched.runbook.title}」转到这里")
                matched = runbooks.Match(book, matched.score, [*matched.hits, why])
            if case is not None:
                case = await run.cases.record_facts(case, observations, primary["incident_id"])
    if case is not None and (run.resume or {}).get("pause_id"):
        await run.cases.record_decision(case["case_id"], "回答了问卷：" + "；".join(run.resume.get("lines") or [])[:400],
                                        run.resume["pause_id"], {"incident_ids": run.resume.get("incident_ids")})
        case = await run.cases.read(case["case_id"])
    options = runbooks.options(matched.runbook, observations) if matched else []
    forbidden = casework.forbidden(case)
    unverified = [e["text"] for e in casework.labelled(case or {})
                  if e["kind"] == "statement" and e["status"] == "active"]
    gaps = gap_rules.compute(incidents=incidents, scope_kind=scope["kind"] if determinate else "window",
                             components_pointed=pointed, case=case, matched=matched, observations=observations,
                             forbidden=forbidden, unverified=unverified)
    run.work = {"case": case, "match": matched, "observations": observations, "options": options, "gaps": gaps,
                "forbidden": forbidden, "incident_ref": ref}
    if case is not None:
        pack["case"] = casework.brief(case)
        emit(runtime, "case", {"case_id": case["case_id"], "status": case["status"]})
    if matched:
        pack["runbook"] = {**matched.runbook.brief(), "signals_seen": matched.hits}
        pack["probes"] = [{"ref": o.ref, "what": o.title, "seen": o.text} for o in observations]
        pack["remediation_options"] = [{k: o[k] for k in ("op", "title", "params", "ready", "met", "unmet", "effect")}
                                       for o in options]
    pack["gaps"] = [g.as_dict() for g in gaps]


async def _probe(runtime: Runtime[Run], book: runbooks.Runbook, incident_id: str,
                 detail: str | None = None) -> list:
    run = runtime.context
    async with step(runtime, "runbook", f"按 Runbook「{book.title}」检查", detail or book.id) as s:
        observations = await runbooks.probe(book, run.core) if run.core else []
        for o in observations:
            o.ref = run.tools.refs.ref("probe", f"{o.probe}@{run.now.isoformat()}", o.text, incident_id)
        s.result = "；".join(f"{o.text} [{o.ref}]" for o in observations) or "没有探针"
        s.refs = [o.ref for o in observations]
        s.ok = all(o.ok for o in observations)
    return observations


def _texts(raw: dict) -> list[str]:
    """What a runbook's signals are looked for in: alert summaries, their samples, the services' warnings and
    errors."""
    texts = []
    for a in raw.get("alerts") or []:
        texts += [str(a.get("summary") or ""), str(a.get("rule_id") or "")]
        texts += [str(x.get("message") or "") for x in a.get("samples") or []]
    for got in (raw.get("logs") or {}).values():
        texts += [str(line.get("message") or "") for line in got.get("errors") or []]
    return texts


async def _runbook_card(runtime: Runtime[Run], pack: dict) -> None:
    """Without a model: the first remediation of the runbook that may be proposed now, as a card."""
    run = runtime.context
    work = run.work
    if not run.propose or not work.get("match"):
        return
    option = next((o for o in work["options"] if o["ready"] and not gap_rules.blocking(work["gaps"], o["op"])
                   and o["op"] not in work["forbidden"]), None)
    if option is None:
        blocker = next((g for g in work["gaps"] if gap_rules.REMEDIATE in g.blocks), None)
        if blocker:
            say(runtime, f"\n\n按 Runbook「{work['match'].runbook.title}」现在还不能提出处置：{blocker.detail}")
        return
    first = (pack.get("incidents") or [{}])[0]
    evidence = [a["ref"] for a in (first.get("alerts") or [])[:2]] + [o.ref for o in work["observations"] if o.ok]
    args = {"kind": option["op"], "incident": work["incident_ref"], "params": option["params"],
            "evidence": evidence[:6], "reason": f"Runbook {work['match'].runbook.id}：" + "；".join(option["met"])}
    try:
        card = await run.propose(args)
    except (action_cards.ProposalError, ValueError) as e:
        say(runtime, f"\n\n没有提出处置：{e}")
        return
    emit(runtime, "action", card)
    run.proposed += 1
    say(runtime, f"\n\n已按 Runbook「{work['match'].runbook.title}」提出操作「{card['title']}」，等待你确认；"
                 "执行后系统会验证是否恢复。")


def _ask_user(runtime: Runtime[Run], arguments: str) -> dict | None:
    try:
        return clarify.model_questionnaire(_args(arguments))
    except ValueError as e:
        log.info("chat: the model's questions were turned down: %s", e)
        emit(runtime, "step", {"id": f"s{runtime.context.steps + 1}", "kind": "ask", "title": "向用户确认",
                               "status": "error", "result": f"问题不合格式（{e}），改为直接回答"})
        runtime.context.steps += 1
        return None


def _args(arguments: str) -> dict:
    args = json.loads(arguments) if arguments and arguments.strip() else {}
    if not isinstance(args, dict):
        raise ValueError("参数要是 JSON 对象")
    return args


def _tool_error(message: str) -> ToolResult:
    return ToolResult(json.dumps({"error": message}, ensure_ascii=False), message, ok=False)


def build_graph():
    graph = StateGraph(ChatState, context_schema=Run)
    for name, node in (("route", route), ("smalltalk", smalltalk), ("knowledge", knowledge), ("locate", locate),
                       ("gather", gather), ("reason", reason), ("verify", verify)):
        graph.add_node(name, node)
    # Going on from a pause point the route is already decided.
    graph.add_conditional_edges(START, lambda s: "locate" if s.get("decision") else "route", ["route", "locate"])
    graph.add_conditional_edges("route", after_route, ["smalltalk", "knowledge", "locate"])
    graph.add_conditional_edges("locate", after_locate, ["gather", END])
    graph.add_edge("gather", "reason")
    graph.add_edge("reason", "verify")
    for last in ("smalltalk", "knowledge", "verify"):
        graph.add_edge(last, END)
    return graph.compile()


def written_answer(pack: dict, diagnosis: bool, degraded: bool) -> str:
    """The answer without a model: the rules' attribution and the alerts themselves, with refs."""
    lines: list[str] = []
    scope = pack["scope"]
    where = scope["window"] + (f"、涉及 {'、'.join(scope['components'])}" if scope.get("components") else "")
    incidents = pack.get("incidents") or []
    if not incidents:
        lines.append(f"{where}没有相关的 Incident。")
    elif "status" not in incidents[0]:
        # Nothing but the list was fetched (prefetch off).
        lines.append(f"{where}有这些 Incident：")
        lines += [f"- [{i['ref']}] {i['title']}" for i in incidents]
    elif diagnosis:
        for i in incidents:
            lines.append(f"**[{i['ref']}] {i['title']}**（{_status(i)}，严重度{_severity(i)}，{i['opened_at']} 起"
                         + f"，最后一条告警 {i['last_alert_at']}（{_ago(i)}）" + "）")
            attribution = i.get("rule_attribution") or []
            lines.append("- 规则归因：" + ("；".join(f"{h['basis']}，可能在 {h['component']}" for h in attribution)
                                          if attribution else "告警没有明确的指向"))
            if i.get("alerts"):
                first = i["alerts"][0]
                lines.append(f"- 最早的告警：{first['summary']} [{first['ref']}]")
                if first.get("samples"):
                    sample = first["samples"][0]
                    lines.append(f"- 样本日志：{sample['message'][:120]} [{sample['ref']}]")
            if i.get("background_analysis"):
                lines.append(f"- 后台分析（{i['background_analysis']['model']}）：{i['background_analysis']['summary']}")
            if attribution:
                lines.append(f"- 建议：检查 {attribution[0]['component']} 的状态、日志和最近的变更")
            lines.append("")
    else:
        lines.append(f"{where}有 {len(incidents)} 个 Incident：")
        for i in incidents:
            pointed = "、".join(h["component"] for h in i.get("rule_attribution") or [])
            lines.append(f"- [{i['ref']}] {i['title']}：{_status(i)}，"
                         + f"最后一条告警 {i['last_alert_at']}（{_ago(i)}）"
                         + (f"，规则指向 {pointed}" if pointed else ""))
    alerts = pack.get("recent_alerts")
    if alerts:
        if lines[-1]:
            lines.append("")
        lines.append(f"最近的告警（{len(alerts)} 条{'，显示前 10 条' if len(alerts) > 10 else ''}）：")
        lines += [f"- {a['at']} {a.get('service') or ''} {a['summary']} [{a['ref']}]" for a in alerts[:10]]
    while lines and not lines[-1]:
        lines.pop()
    lines.append("")
    lines.append("> 大模型调用失败，以上是规则的结论和原始告警。" if degraded
                 else "> 没有使用大模型：以上是规则的结论和原始告警。")
    return "\n".join(lines)


async def _page_incident(run: Run, found) -> dict | None:
    """The incident on the page the question was asked from, when the question is about it: no other incident or
    time named, and the components it names (下单 is order-service) are among the incident's own."""
    incident_id = (run.page or {}).get("incident_id")
    if not incident_id or found.minutes is not None:
        return None
    fetched = await _fetch(run.tools, (incident_id,))
    if not fetched:
        return None
    incident = fetched[0]
    own = set(incident.get("services") or []) | set(incident.get("subjects") or []) | set(
        incident.get("shared_dependencies") or [])
    return incident if set(found.services) <= own else None


async def _fetch(tools: ChatTools, ids: tuple[str, ...]) -> list[dict]:
    incidents = []
    for incident_id in ids:
        try:
            incidents.append(await tools.incident(incident_id))
        except CoreApiError as e:
            if " 404 " not in str(e):
                raise
    return incidents


def _timing(tools: ChatTools, incidents: list[dict]) -> list[str]:
    """How the incidents lie in time, oldest first, worked out here rather than left to the model: one that
    started after another had gone quiet cannot be explained by it without evidence."""
    rows = sorted(incidents, key=lambda i: i["opened_at"])
    lines = []
    for n, i in enumerate(rows):
        ref = tools.brief(i)["ref"]
        line = f"{ref}：{tools.local(i['opened_at'])} 至 {tools.local(i['last_alert_at'])}"
        if n:
            before = rows[n - 1]
            gap = (parse_time(i["opened_at"]) - parse_time(before["last_alert_at"])).total_seconds()
            prev = tools.brief(before)["ref"]
            line += (f"，在 {prev} 最后一条告警之后 {_seconds(gap)}才开始" if gap > 0
                     else f"，和 {prev} 时间重叠")
        lines.append(line)
    return lines


def _seconds(seconds: float) -> str:
    minutes, rest = divmod(int(seconds), 60)
    if minutes >= 60:
        return f"{minutes // 60} 小时 {minutes % 60} 分"
    return f"{minutes} 分 {rest} 秒" if minutes else f"{rest} 秒"


def _pointed_at(incident: dict) -> list[dict]:
    return [{"component": h.component, "basis": h.cause} for h in rule_attribution(incident, [])]


def _focus(run: Run, state: ChatState, cites: list[dict]) -> dict:
    """What the conversation is about after this answer: the incidents it cited, else the ones it found."""
    ids = []
    for c in cites:
        if c["verified"]:
            incident_id = c["id"] if c["kind"] == "incident" else c.get("incident_id")
            if incident_id and incident_id not in ids:
                ids.append(incident_id)
    if not ids:
        ids = [i["incident_id"] for i in state.get("incidents", [])[:DETAILED]]
    if not ids:
        return run.memory["focus"]
    refs = run.tools.refs
    incidents = []
    for incident_id in ids[:DETAILED]:
        ref = refs.ref("incident", incident_id, "")
        incidents.append({"incident_id": incident_id, "ref": ref, "title": refs.resolve(ref).label})
    services = list(state["decision"].entities.services) or run.memory["focus"].get("services", [])
    return {"incidents": incidents, "services": services}


def _ago(incident: dict) -> str:
    minutes = incident["minutes_since_last_alert"]
    return "1 分钟内" if minutes == 0 else f"{minutes} 分钟前"


def _status(incident: dict) -> str:
    return STATUS_LABEL.get(incident["status"], incident["status"])


def _severity(incident: dict) -> str:
    return SEVERITY_LABEL.get(incident["severity"], incident["severity"])


def _arguments(arguments: str) -> str | None:
    text = (arguments or "").strip()
    return None if text in ("", "{}") else text[:200]


def _span(minutes: int) -> str:
    if minutes % (24 * 60) == 0:
        return f"{minutes // (24 * 60)} 天"
    if minutes % 60 == 0:
        return f"{minutes // 60} 小时"
    return f"{minutes} 分钟"


def _error_text(e: Exception) -> str:
    text = str(e) or type(e).__name__
    return text[:300]


def _ms(started: float) -> int:
    return int((time.monotonic() - started) * 1000)
