"""One question from start to finish: recall the session, run the graph, pass its events on as they come, and keep
the answer with everything that led to it (route, steps, thinking, citations, timings), so that a reloaded page
shows it the same way. A question that is cancelled (the browser went away) or fails is kept too, marked so.

An answer may end at a pause point (a questionnaire, chat.clarify). The next message either answers it, and the
answer goes on from there (resume_plan claims the pause first, so it is answered once), or asks something else, and
the pause is superseded. Actions the model proposed are confirmed or declined through the ActionDesk (chat.actions),
outside any answer."""

import asyncio
import hashlib
import json
import logging
import time
import uuid
from collections.abc import AsyncIterator
from contextlib import aclosing
from datetime import datetime
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from ..core_api import CoreApi, CoreApiError, CoreWriter
from . import casework, clarify, prompts, runbooks
from .actions import MAX_PER_ANSWER, ActionDesk, ProposalError
from .casework import CaseDesk, CaseError
from .evidence import RefTable
from .graph import Run, build_graph
from .knowledge import Knowledge
from .llm import StreamingModel
from .memory import NEW_TITLE, ChatStore, Memory
from .router import SERVICE_ALIASES, Decision, Router
from .tools import ChatTools
from .usage import Ledger, Metered

log = logging.getLogger(__name__)

CATALOG_TTL_S = 300


class UnknownSession(KeyError):
    pass


class PauseError(Exception):
    """The reply cannot go on from that questionnaire: no such pause, answered already, expired, or answers that do
    not fit (status 404, 409, 400)."""

    def __init__(self, status: int, code: str, detail: str):
        super().__init__(detail)
        self.status, self.code, self.detail = status, code, detail


class DuplicateRequest(Exception):
    """This session already has a run for the request_id (the same request sent twice)."""


def snapshot_digest(snapshot: dict) -> str:
    text = json.dumps(snapshot, sort_keys=True, separators=(",", ":"), ensure_ascii=False, default=str)
    return "sha256:" + hashlib.sha256(text.encode("utf-8")).hexdigest()


def run_status(status: str, state: dict, proposed: int) -> str:
    """How the run ended, apart from whether the case is handled (docs/agent-design.md §3.2)."""
    if status != "completed":
        return status
    if state.get("pause"):
        return "paused_question"
    if proposed:
        return "paused_action"
    return "degraded" if state.get("degraded") else "answered"


class Recorder:
    """Puts the streamed events back together into what is stored with the answer."""

    def __init__(self) -> None:
        self.started = time.monotonic()
        self.route: dict | None = None
        self.items: list[dict] = []
        self.answer = ""
        self.citations: list[dict] = []
        self.actions: list[dict] = []
        self.memories: list[dict] = []
        self.questionnaire: dict | None = None
        self.case: dict | None = None
        self.error: str | None = None
        self.first_answer_ms: int | None = None
        self.first_event_ms: int | None = None

    def record(self, event: str, data: dict) -> None:
        if self.first_event_ms is None and event != "meta":
            self.first_event_ms = self.elapsed_ms()
        if event == "route":
            self.route = data
        elif event == "step":
            for i, item in enumerate(self.items):
                if item["type"] == "step" and item["id"] == data["id"]:
                    self.items[i] = {"type": "step", **data}
                    break
            else:
                self.items.append({"type": "step", **data})
        elif event == "thinking":
            if self.items and self.items[-1]["type"] == "thought" and not self.items[-1].get("interim"):
                self.items[-1]["text"] += data["delta"]
            else:
                self.items.append({"type": "thought", "text": data["delta"]})
        elif event == "answer":
            if self.first_answer_ms is None:
                self.first_answer_ms = self.elapsed_ms()
            self.answer += data["delta"]
        elif event == "interim":
            self.items.append({"type": "thought", "text": data["text"], "interim": True})
            self.answer = ""
            self.first_answer_ms = None
        elif event == "citations":
            self.citations = data["items"]
        elif event == "action":
            self.actions.append(data)
        elif event == "memory":
            self.memories.append(data)
        elif event == "questionnaire":
            self.questionnaire = data
        elif event == "case":
            self.case = {**(self.case or {}), **data}
        elif event == "error":
            self.error = data["message"]

    def elapsed_ms(self) -> int:
        return int((time.monotonic() - self.started) * 1000)

    def trace(self, extra: dict) -> dict:
        return {"route": self.route, "items": self.items, "citations": self.citations,
                **({"actions": self.actions} if self.actions else {}),
                **({"memories": self.memories} if self.memories else {}),
                **({"questionnaire": self.questionnaire} if self.questionnaire else {}),
                **({"case": self.case} if self.case else {}),
                **({"error": self.error} if self.error else {}),
                "metrics": {"duration_ms": self.elapsed_ms(), "first_event_ms": self.first_event_ms,
                            "first_answer_ms": self.first_answer_ms, **extra}}


class ChatAgent:
    def __init__(self, core: CoreApi, store: ChatStore, model: StreamingModel | None, *, max_tool_calls: int = 4,
                 history_turns: int = 3, router_timeout_s: float = 8, prefetch: bool = True,
                 writer: CoreWriter | None = None, action_ttl_s: float = 900, casework_on: bool = False):
        self._core = core
        self._store = store
        self.model = model
        self._router_timeout_s = router_timeout_s
        self._memory = Memory(store, history_turns)
        self._max_tool_calls = max_tool_calls
        self._prefetch = prefetch
        self._graph = build_graph()
        self._catalog: tuple[float, set[str]] | None = None
        self._background: set[asyncio.Task] = set()
        self._summarizing: set[str] = set()
        # Without a writer actions can still be proposed; confirming one says it cannot be run here.
        self.desk = ActionDesk(store, core, writer, action_ttl_s)
        self.knowledge = Knowledge(store)
        # The case and the runbooks (docs/agent-design.md §13.4); off: the assistant as it was before.
        self.cases = CaseDesk(core, writer) if casework_on else None
        self.books = runbooks.load() if casework_on else []

    def resume_plan(self, session_id: str, pause_id: str, answers: list[dict]) -> dict:
        """Checks a reply to a questionnaire and claims the pause (compare-and-set from open), before the answer
        starts streaming: what ask then goes on with."""
        pause = self._store.pause(pause_id)
        if pause is None or pause["session_id"] != session_id:
            raise PauseError(404, "PAUSE_NOT_FOUND", f"No questionnaire {pause_id} in this session")
        if pause["status"] != "open":
            raise PauseError(409, "PAUSE_CLOSED", "这组问题已经回答过，或者已经被新的问题取代")
        if clarify.expired(pause):
            self._store.close_pause(pause_id, "expired")
            raise PauseError(409, "PAUSE_EXPIRED", "这组问题已过期，数据可能已经变了，请重新提问")
        try:
            meaning = clarify.read_answers(pause, answers)
        except clarify.AnswerError as e:
            raise PauseError(400, "VALIDATION_FAILED", str(e)) from e
        if not self._store.close_pause(pause_id, "answered", {"answers": answers, **meaning}):
            raise PauseError(409, "PAUSE_CLOSED", "这组问题已经回答过")
        return {"pause_id": pause_id, "question": pause["state"]["question"],
                "decision": Decision.from_dict(pause["state"]["decision"]), **meaning}

    def replay(self, session_id: str, request_id: str) -> dict | None:
        """The run this request already had: its record and the answer it gave."""
        found = self._store.run_for(session_id, request_id)
        if found is None:
            return None
        return {"run": found, "message": self._store.message(found["message_id"])}

    async def chain(self, run_id: str) -> dict | None:
        """From the request to the verdict (docs/agent-design.md §15.2): the run, what was asked and answered, its
        cards with their receipts, what core-service did with them and what the run wrote on the case. core-service
        down: what is known here, and core_error."""
        run = self._store.run(run_id)
        if run is None:
            return None
        message = self._store.message(run["message_id"])
        before = self._store.messages(run["session_id"], message["seq"] - 2)[0] if message else None
        cards = [a for a in self._store.actions(run["session_id"]) if a["message_id"] == run["message_id"]]
        case_ids = list(dict.fromkeys(
            [*([run["case_id"]] if run["case_id"] else []), *(a["case"]["case_id"] for a in cards if a.get("case"))]))
        cases, remediations, verifications, core_error = [], [], [], None
        try:
            for case_id in case_ids:
                cases.append(await asyncio.to_thread(self._core.case, case_id))
                remediations += await asyncio.to_thread(self._core.remediations, case_id)
                verifications += await asyncio.to_thread(self._core.verifications, case_id)
        except CoreApiError as e:
            log.warning("chat: run %s: core-service did not answer: %s", run_id, e)
            core_error = str(e)
        by_action = {r["action_id"]: r for r in remediations}

        def traced(card: dict) -> dict:
            done = by_action.get(card["action_id"])
            return {**card, "remediation": done, "verifications": [
                v for v in verifications if done and v["remediation_id"] == done["remediation_id"]]}

        return {"run": run, "question": before["content"] if before and before["role"] == "user" else None,
                "answer": message and {"content": message["content"], "status": message["status"],
                                       "created_at": message["created_at"]},
                "actions": [traced(c) for c in cards],
                "cases": [{"case_id": c["case_id"], "title": c["title"], "status": c["status"],
                           "state_version": c["state_version"],
                           "entries": [e for e in c["entries"] if e.get("run_id") == run_id]} for c in cases],
                "core_error": core_error}

    async def ask(self, session_id: str, question: str, tz_name: str | None = None,
                  thinking: bool | None = None, plan: dict | None = None, page: dict | None = None,
                  request_id: str | None = None) -> AsyncIterator[tuple[str, dict]]:
        """The events of one answer, ending with done. thinking: the model reasons at length before it answers
        (slower, and its reasoning streams), None for the configured default. plan: going on from a questionnaire
        (resume_plan); question is then the user's answers in words."""
        session = self._store.session(session_id)
        if session is None:
            raise UnknownSession(session_id)
        message_id = str(uuid.uuid4())
        run_id = str(uuid.uuid4())
        trigger = "questionnaire_reply" if plan else "user_message"
        if not self._store.add_run(run_id, session_id, request_id, trigger, message_id):
            raise DuplicateRequest(request_id)
        if plan is None:
            waiting = self._store.open_pause(session_id)
            if waiting:
                self._store.close_pause(waiting["pause_id"], "superseded")
        tz = _zone(tz_name)
        now = datetime.now(tz)
        memory = self._memory.recall(session)
        memory["actions"] = [{"title": a["title"], "status": a["status"],
                              "summary": (a.get("receipt") or {}).get("summary")}
                             for a in self._store.actions(session_id)[-5:]]
        self._store.add_message(session_id, "user", question)
        if session["title"] == NEW_TITLE:
            self._store.update_session(session_id, title=(plan["question"] if plan else question).strip()
                                       .replace("\n", " ")[:30])
        refs = RefTable(session["refs"])
        tools = ChatTools(self._core, refs, now, tz)
        catalog = await self._catalog_names(tools)
        proposed: list[str] = []
        ledger = Ledger()
        metered = Metered(self.model, ledger) if self.model else None
        cases = self.cases.for_run(run_id) if self.cases else None
        # What the user said, for update_case's quotes: this question and the earlier ones.
        said = [question, *(plan["lines"] if plan else []),
                *(m["content"] for m in memory["history"] if m["role"] == "user")]

        async def propose(args: dict) -> dict:
            if len(proposed) >= MAX_PER_ANSWER:
                raise ProposalError(f"一个回答最多提出 {MAX_PER_ANSWER} 个操作")
            card = await self.desk.propose(session_id, message_id, refs, args, run.work)
            # The card can be confirmed before this answer ends: its evidence must be in the stored refs by then.
            self._store.update_session(session_id, refs=refs.as_json())
            proposed.append(card["action_id"])
            return card

        def remember(args: dict) -> dict:
            return self.knowledge.propose(session_id, message_id, refs, catalog, args)

        async def update_case(args: dict) -> dict:
            case = run.work.get("case")
            if case is None or cases is None:
                raise CaseError("这次回答没有处置单")
            noted = await cases.update(case["case_id"], args, refs, said)
            self._store.update_session(session_id, refs=refs.as_json())
            return noted

        asked = question if plan is None else plan["question"] + "\n\n用户补充：\n" + "\n".join(plan["lines"])
        run = Run(question=asked, now=now, tz=tz, memory=memory, tools=tools, catalog=catalog,
                  router=Router(metered.for_purpose("route") if metered else None, self._router_timeout_s),
                  model=metered, max_tool_calls=self._max_tool_calls,
                  prefetch=self._prefetch or self.model is None, thinking=thinking,
                  propose=propose, remember=remember if self.model else None,
                  known=self.knowledge.relevant, may_ask=plan is None,
                  resume={"incident_ids": plan.get("incident_ids"), "pause_id": plan["pause_id"],
                          "lines": plan["lines"]} if plan else None,
                  cases=cases, books=self.books, core=self._core,
                  update_case=update_case if self.model and cases and cases.writable else None,
                  page=page, ledger=ledger)
        recorder = Recorder()
        state: dict = {}
        # Until the graph ends, a way out is the browser going away.
        status = "cancelled"

        def save() -> dict:
            decision = state.get("decision")
            ledger.settle()
            trace = recorder.trace({"run_id": run_id, "tool_calls": state.get("tool_calls", 0),
                                    "degraded": state.get("degraded", False),
                                    "model": self.model.name if self.model else None, "thinking": thinking,
                                    **ledger.summary()})
            self._store.add_message(session_id, "assistant", recorder.answer, status, trace, message_id)
            updates: dict = {"refs": refs.as_json()}
            if status == "completed" and state.get("focus") is not None:
                updates["focus"] = state["focus"]
            self._store.update_session(session_id, **updates)
            case = run.work.get("case")
            snapshot = self._snapshot(run, now, trigger, plan, page, memory, session)
            ended = run_status(status, state, len(proposed))
            self._store.finish_run(run_id, ended, case["case_id"] if case else None, snapshot,
                                   snapshot_digest(snapshot), ledger.as_dict())
            return {"message_id": message_id, "status": status, "run_id": run_id, "run_status": ended,
                    "case_id": case["case_id"] if case else None,
                    "route": decision.route if decision else None, **trace["metrics"],
                    "paused": status == "completed" and bool(state.get("pause")), "actions": len(proposed)}

        yield "meta", {"message_id": message_id, "session_id": session_id}
        start: dict = {}
        if plan:
            # The route was decided when the question was first asked; the answer goes on from locate.
            start = {"decision": plan["decision"]}
            resumed = {**plan["decision"].as_dict(), "resumed": True, "duration_ms": 0,
                       "reason": "接着回答上一个问题：" + plan["question"][:40]}
            recorder.record("route", resumed)
            yield "route", resumed
        try:
            async with aclosing(self._graph.astream(start, context=run, stream_mode=["custom", "values"])) as events:
                async for mode, chunk in events:
                    if mode == "custom":
                        recorder.record(chunk["event"], chunk["data"])
                        yield chunk["event"], chunk["data"]
                    else:
                        state = chunk
            status = "completed"
            if state.get("pause"):
                record = clarify.pause_record(state["pause"], pause_id=str(uuid.uuid4()), session_id=session_id,
                                              message_id=message_id, question=run.question,
                                              decision=state["decision"])
                self._store.add_pause(record)
                shown = {"pause_id": record["pause_id"], "source": state["pause"]["source"],
                         "questions": record["questions"], "expires_at": record["expires_at"]}
                recorder.record("questionnaire", shown)
                yield "questionnaire", shown
        except CoreApiError as e:
            status = "failed"
            log.warning("chat: core-service failed: %s", e)
            message = f"查不到数据：core-service 不可用或返回错误（{e}）"
            recorder.record("error", {"message": message})
            yield "error", {"message": message}
        except Exception as e:
            status = "failed"
            log.exception("chat: the answer failed")
            message = f"回答失败：{type(e).__name__}: {str(e)[:200]}"
            recorder.record("error", {"message": message})
            yield "error", {"message": message}
        finally:
            # Every way out keeps the answer, a cancelled one as far as it got; no awaiting, the task may be ending.
            done = save()
        self._maybe_summarize(session_id)
        yield "done", done

    @staticmethod
    def _snapshot(run: Run, now: datetime, trigger: str, plan: dict | None, page: dict | None, memory: dict,
                  session: dict) -> dict:
        """What the model was given, by part and version (docs/agent-design.md §3.3): enough to tell two runs apart
        and to see why an answer went the way it did."""
        work = run.work
        case = work.get("case")
        match = work.get("match")
        return {"time": now.isoformat(), "tz": str(run.tz), "trigger": trigger,
                "resume_reason": ("回答了问卷：" + plan["question"][:80]) if plan else None, "page": page,
                "case": casework.brief(case) if case else None,
                "case_state_version": case.get("state_version") if case else None,
                "runbook": {"id": match.runbook.id, "version": match.runbook.version} if match else None,
                "probes": [{"ref": o.ref, "seen": o.text} for o in work.get("observations") or []],
                "gaps": [g.as_dict() for g in work.get("gaps") or []],
                "open": {"actions": memory.get("actions") or []},
                "memory": {"focus": memory.get("focus"), "summary_through": session.get("summarized_through", 0),
                           "history_messages": len(memory.get("history") or [])}}

    async def _catalog_names(self, tools: ChatTools) -> set[str]:
        if self._catalog and time.monotonic() - self._catalog[0] < CATALOG_TTL_S:
            return self._catalog[1]
        try:
            names = {n["name"] for n in (await tools.topology())["nodes"]}
        except CoreApiError as e:
            log.warning("chat: no catalog from core-service (%s); using the known names", e)
            return set(SERVICE_ALIASES)
        self._catalog = (time.monotonic(), names)
        return names

    def _maybe_summarize(self, session_id: str) -> None:
        # One summary at a time per session in this process; the compare-and-set in save_summary covers the rest
        # (another process on the same file, a summary that outlives this guard).
        if self.model is None or session_id in self._summarizing:
            return
        session = self._store.session(session_id)
        due = self._memory.due_for_summary(session) if session else []
        if not due:
            return
        self._summarizing.add(session_id)
        task = asyncio.create_task(self._summarize(session_id, session.get("summary"), due,
                                                   session.get("summarized_through", 0)))
        self._background.add(task)
        task.add_done_callback(self._background.discard)
        task.add_done_callback(lambda _: self._summarizing.discard(session_id))

    async def _summarize(self, session_id: str, summary: str | None, messages: list[dict], since: int) -> None:
        try:
            text = await self.model.quick(prompts.summary_messages(summary, messages), timeout_s=30)
        except Exception as e:  # noqa: BLE001 - the recent turns are still there; try again after the next one
            log.warning("chat: summarizing session %s failed: %s", session_id, e)
            return
        if text.strip() and not self._store.save_summary(session_id, text.strip()[:2000], messages[-1]["seq"],
                                                         expected_through=since):
            log.info("chat: session %s was summarized meanwhile; this summary is dropped", session_id)

    async def close(self) -> None:
        for task in list(self._background):
            task.cancel()


def _zone(name: str | None) -> ZoneInfo:
    try:
        return ZoneInfo(name) if name else ZoneInfo("UTC")
    except (ZoneInfoNotFoundError, ValueError):
        return ZoneInfo("UTC")
