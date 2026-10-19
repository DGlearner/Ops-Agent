"""One question from start to finish: recall the session, run the graph, pass its events on as they come, and keep
the answer with everything that led to it (route, steps, thinking, citations, timings), so that a reloaded page
shows it the same way. A question that is cancelled (the browser went away) or fails is kept too, marked so."""

import asyncio
import logging
import time
import uuid
from collections.abc import AsyncIterator
from contextlib import aclosing
from datetime import datetime
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from ..core_api import CoreApi, CoreApiError
from . import prompts
from .evidence import RefTable
from .graph import Run, build_graph
from .llm import StreamingModel
from .memory import NEW_TITLE, ChatStore, Memory
from .router import SERVICE_ALIASES, Router
from .tools import ChatTools

log = logging.getLogger(__name__)

CATALOG_TTL_S = 300


class UnknownSession(KeyError):
    pass


class Recorder:
    """Puts the streamed events back together into what is stored with the answer."""

    def __init__(self) -> None:
        self.started = time.monotonic()
        self.route: dict | None = None
        self.items: list[dict] = []
        self.answer = ""
        self.citations: list[dict] = []
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
        elif event == "error":
            self.error = data["message"]

    def elapsed_ms(self) -> int:
        return int((time.monotonic() - self.started) * 1000)

    def trace(self, extra: dict) -> dict:
        return {"route": self.route, "items": self.items, "citations": self.citations,
                **({"error": self.error} if self.error else {}),
                "metrics": {"duration_ms": self.elapsed_ms(), "first_event_ms": self.first_event_ms,
                            "first_answer_ms": self.first_answer_ms, **extra}}


class ChatAgent:
    def __init__(self, core: CoreApi, store: ChatStore, model: StreamingModel | None, *, max_tool_calls: int = 4,
                 history_turns: int = 3, router_timeout_s: float = 8, prefetch: bool = True):
        self._core = core
        self._store = store
        self.model = model
        self._router = Router(model, router_timeout_s)
        self._memory = Memory(store, history_turns)
        self._max_tool_calls = max_tool_calls
        self._prefetch = prefetch
        self._graph = build_graph()
        self._catalog: tuple[float, set[str]] | None = None
        self._background: set[asyncio.Task] = set()

    async def ask(self, session_id: str, question: str, tz_name: str | None = None,
                  thinking: bool | None = None) -> AsyncIterator[tuple[str, dict]]:
        """The events of one answer, ending with done. thinking: the model reasons at length before it answers
        (slower, and its reasoning streams), None for the configured default."""
        session = self._store.session(session_id)
        if session is None:
            raise UnknownSession(session_id)
        tz = _zone(tz_name)
        now = datetime.now(tz)
        memory = self._memory.recall(session)
        self._store.add_message(session_id, "user", question)
        if session["title"] == NEW_TITLE:
            self._store.update_session(session_id, title=question.strip().replace("\n", " ")[:30])
        refs = RefTable(session["refs"])
        tools = ChatTools(self._core, refs, now, tz)
        run = Run(question=question, now=now, tz=tz, memory=memory, tools=tools,
                  catalog=await self._catalog_names(tools), router=self._router, model=self.model,
                  max_tool_calls=self._max_tool_calls, prefetch=self._prefetch or self.model is None,
                  thinking=thinking)
        message_id = str(uuid.uuid4())
        recorder = Recorder()
        state: dict = {}
        # Until the graph ends, a way out is the browser going away.
        status = "cancelled"

        def save() -> dict:
            decision = state.get("decision")
            trace = recorder.trace({"tool_calls": state.get("tool_calls", 0), "degraded": state.get("degraded", False),
                                    "model": self.model.name if self.model else None, "thinking": thinking})
            self._store.add_message(session_id, "assistant", recorder.answer, status, trace, message_id)
            updates: dict = {"refs": refs.as_json()}
            if status == "completed" and state.get("focus") is not None:
                updates["focus"] = state["focus"]
            self._store.update_session(session_id, **updates)
            return {"message_id": message_id, "status": status,
                    "route": decision.route if decision else None, **trace["metrics"]}

        yield "meta", {"message_id": message_id, "session_id": session_id}
        try:
            async with aclosing(self._graph.astream({}, context=run, stream_mode=["custom", "values"])) as events:
                async for mode, chunk in events:
                    if mode == "custom":
                        recorder.record(chunk["event"], chunk["data"])
                        yield chunk["event"], chunk["data"]
                    else:
                        state = chunk
            status = "completed"
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
        if self.model is None:
            return
        session = self._store.session(session_id)
        due = self._memory.due_for_summary(session) if session else []
        if not due:
            return
        task = asyncio.create_task(self._summarize(session_id, session.get("summary"), due))
        self._background.add(task)
        task.add_done_callback(self._background.discard)

    async def _summarize(self, session_id: str, summary: str | None, messages: list[dict]) -> None:
        try:
            text = await self.model.quick(prompts.summary_messages(summary, messages), timeout_s=30)
        except Exception as e:  # noqa: BLE001 - the recent turns are still there; try again after the next one
            log.warning("chat: summarizing session %s failed: %s", session_id, e)
            return
        if text.strip():
            self._store.update_session(session_id, summary=text.strip()[:2000],
                                       summarized_through=messages[-1]["seq"])

    async def close(self) -> None:
        for task in list(self._background):
            task.cancel()


def _zone(name: str | None) -> ZoneInfo:
    try:
        return ZoneInfo(name) if name else ZoneInfo("UTC")
    except (ZoneInfoNotFoundError, ValueError):
        return ZoneInfo("UTC")
