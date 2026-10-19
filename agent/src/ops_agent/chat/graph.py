"""The orchestration, as a LangGraph state graph:

    route ─┬─ chitchat / out_of_scope ──────────────────────────▶ smalltalk
           ├─ ops_knowledge ────────────────────────────────────▶ knowledge
           └─ ops_diagnosis / ops_query ─▶ locate ─▶ gather ─▶ reason ─▶ verify

For an ops question the evidence is fetched before the model is asked anything: locate finds the incidents the
question is about (an id in it, the incidents the conversation is about, or the ones with alerts in the time window,
widened to a day when the window is empty), gather fetches their alerts, samples, rule attribution and background
analysis in parallel. The model then reasons over that pack and may look further with up to chat_max_tool_calls tool
calls; verify checks every ref it cited. Without a model, or when it fails, the answer is written from the pack.

Every node reports what it does through the stream writer, and those events go to the browser as they happen: route,
step (running, then done or error, with timings), thinking (the model's reasoning), answer, interim (text the model
wrote before deciding to call a tool: a note, not the answer), citations.
"""

import asyncio
import logging
import time
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from dataclasses import dataclass, field
from datetime import datetime
from typing import Any, TypedDict
from zoneinfo import ZoneInfo

from langgraph.graph import END, START, StateGraph
from langgraph.runtime import Runtime

from ..baseline import rule_attribution
from ..core_api import CoreApiError, parse_time
from . import prompts
from .evidence import citations
from .llm import StreamingModel
from .router import OPS_ROUTES, Decision, Router
from .tools import ChatTools

log = logging.getLogger(__name__)

DEFAULT_MINUTES = 60
WIDENED_MINUTES = 24 * 60
DETAILED = 3
STATUS_LABEL = {"open": "未处理", "acknowledged": "已确认", "investigating": "排查中", "mitigated": "已缓解",
                "resolved": "已解决", "closed": "已关闭"}
SEVERITY_LABEL = {"critical": "严重", "high": "高", "medium": "中", "low": "低", "info": "提示"}
TOOL_TITLE = {"list_incidents": "查询 Incident", "get_incident": "查看 Incident 详情", "search_alerts": "查询告警",
              "search_logs": "查询日志", "count_logs": "统计日志", "get_topology": "查看服务依赖"}


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


class Step:
    def __init__(self) -> None:
        self.result = ""
        self.ok = True


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
    try:
        yield current
    except Exception as e:
        emit(runtime, "step", {**base, "status": "error", "result": current.result or _error_text(e),
                               "duration_ms": _ms(started)})
        raise
    emit(runtime, "step", {**base, "status": "done" if current.ok else "error", "result": current.result,
                           "duration_ms": _ms(started)})


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
    async with step(runtime, "locate", "定位相关的 Incident") as s:
        widened = False
        minutes = found.minutes or DEFAULT_MINUTES
        if found.incident_ids:
            kind, how = "named", "问题里给出的 Incident"
            incidents = await _fetch(run.tools, found.incident_ids[:DETAILED])
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
        s.result = f"{how}：{len(incidents)} 个" + (f"（最近 {_span(DEFAULT_MINUTES)}没有，扩大到 {_span(minutes)}）"
                                                   if widened else "")
    return {"incidents": incidents, "scope": {"kind": kind, "how": how, "minutes": minutes, "widened": widened,
                                              "components": list(found.services)}}


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
            result = await run.tools.detail(incident)
            logged = result.get("service_logs", {})
            s.result = f"{len(result['alerts'])} 条告警，规则指向 " + (
                "、".join(h["component"] for h in result["rule_attribution"]) or "不明确") + (
                "；日志：" + "、".join(f"{svc} {sum(c['count'] for c in got['events'])} 行"
                                      f"（警告和错误 {len(got['warnings_and_errors'])} 条）" for svc, got in logged.items())
                if logged else "")
            return result

    async def alerts() -> list[dict]:
        async with step(runtime, "fetch", "查询最近的告警") as s:
            result = await run.tools.recent_alerts(scope["minutes"], scope["components"][0]
                                                   if len(scope["components"]) == 1 else None)
            s.result = f"{len(result)} 条"
            return result

    status = state["decision"].route == "ops_query" and scope["kind"] == "window"
    fetched = await asyncio.gather(*(detail(i) for i in incidents[:DETAILED]),
                                   *([alerts()] if status or not incidents else []))
    pack["incidents"] = list(fetched[:len(incidents[:DETAILED])])
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
    return {"pack": pack}


async def reason(state: ChatState, runtime: Runtime[Run]) -> dict:
    run = runtime.context
    diagnosis = state["decision"].route == "ops_diagnosis"
    if run.model is None:
        say(runtime, written_answer(state["pack"], diagnosis, degraded=False))
        return {"answer": "".join(run.answered), "tool_calls": 0, "degraded": False}
    messages = prompts.ops_messages(run.question, state["pack"], diagnosis=diagnosis, now=run.now, tz=str(run.tz),
                                    focus=run.memory["focus"], summary=run.memory["summary"],
                                    history=run.memory["history"], max_tool_calls=run.max_tool_calls)
    calls = 0
    try:
        for _ in range(run.max_tool_calls + 2):
            exhausted = calls >= run.max_tool_calls
            turn = await run.model.stream(messages, None if exhausted else run.tools.specs(),
                                          on_reasoning=lambda d: emit(runtime, "thinking", {"delta": d}),
                                          on_content=lambda d: say(runtime, d), thinking=run.thinking)
            if not turn.tool_calls:
                return {"answer": turn.content, "tool_calls": calls, "degraded": False}
            if run.answered:
                # Written before the model decided to look further: a note on the way, not the answer.
                emit(runtime, "interim", {"text": "".join(run.answered)})
                run.answered.clear()
            messages.append(turn.as_message())
            for call in turn.tool_calls:
                if calls >= run.max_tool_calls:
                    messages.append({"role": "tool", "tool_call_id": call.id,
                                     "content": '{"error": "' + prompts.FINAL_ANSWER + '"}'})
                    continue
                calls += 1
                async with step(runtime, "tool", TOOL_TITLE.get(call.name, call.name),
                                _arguments(call.arguments)) as s:
                    result = await run.tools.call(call.name, call.arguments)
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
    answer = state.get("answer") or "".join(run.answered)
    async with step(runtime, "check", "核对引用") as s:
        cites = citations(answer, run.tools.refs)
        unknown = [c["ref"] for c in cites if not c["verified"]]
        s.result = f"{len(cites)} 处引用" + (f"，{'、'.join(unknown)} 不在查到的数据里" if unknown else "，都有出处")
        s.ok = not unknown
    emit(runtime, "citations", {"items": cites})
    return {"focus": _focus(run, state, cites)}


def build_graph():
    graph = StateGraph(ChatState, context_schema=Run)
    for name, node in (("route", route), ("smalltalk", smalltalk), ("knowledge", knowledge), ("locate", locate),
                       ("gather", gather), ("reason", reason), ("verify", verify)):
        graph.add_node(name, node)
    graph.add_edge(START, "route")
    graph.add_conditional_edges("route", after_route, ["smalltalk", "knowledge", "locate"])
    graph.add_edge("locate", "gather")
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
