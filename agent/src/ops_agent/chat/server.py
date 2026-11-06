"""The chat's HTTP API (FastAPI), on 127.0.0.1:8083 by default; the console's dev server forwards /api/v1/chat here.

    POST   /api/v1/chat/sessions                  a new session
    GET    /api/v1/chat/sessions                  the latest sessions
    GET    /api/v1/chat/sessions/{id}             one session with its messages and their traces
    DELETE /api/v1/chat/sessions/{id}
    POST   /api/v1/chat/sessions/{id}/messages    ask, or answer the open questionnaire (reply); Server-Sent Events
    GET    /api/v1/chat/sessions/{id}/actions     the action cards and their receipts
    POST   /api/v1/chat/sessions/{id}/actions/{action_id}/confirm   {"version": n}: run it, once; the receipt
    POST   /api/v1/chat/sessions/{id}/actions/{action_id}/decline   {"version": n}
    GET    /api/v1/chat/sessions/{id}/runs        the runs of a session
    GET    /api/v1/chat/runs/{run_id}             a run from the request to the verdict, with what it spent
    GET    /api/v1/chat/memories?status=candidate memories across sessions
    POST   /api/v1/chat/memories/{id}/approve     {"version": n}; .../reject likewise
    GET    /api/v1/chat/health

One question at a time per session (409 otherwise). When the browser goes away the answer is cancelled and kept as
far as it got. Errors are RFC 9457 Problem Details with a code, as core-service's.
"""

import asyncio
import json
import logging
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse, Response
from pydantic import BaseModel, Field
from sse_starlette import EventSourceResponse
from starlette.background import BackgroundTask

from .actions import ActionConflict, ActionNotFound
from .agent import ChatAgent, DuplicateRequest, PauseError, UnknownSession
from .memory import ChatStore

log = logging.getLogger(__name__)

PING_S = 15


class Answer(BaseModel):
    id: str = Field(max_length=20)
    choices: list[str] = Field(default=[], max_length=5)
    text: str | None = Field(default=None, max_length=200)


class Reply(BaseModel):
    """Answers to the questionnaire the last answer ended with."""
    pause_id: str = Field(max_length=64)
    answers: list[Answer] = Field(min_length=1, max_length=4)


class Decided(BaseModel):
    # The card's version the person saw when they decided.
    version: int = Field(ge=1)
    # The digest of what the card says will be done (a remediation is confirmed only with it).
    payload_digest: str | None = Field(default=None, max_length=80)


class Page(BaseModel):
    """What the page the question was asked from shows: only to resolve "这个", never an authorization."""
    incident_id: str | None = Field(default=None, max_length=64)
    case_id: str | None = Field(default=None, max_length=64)
    case_version: int | None = None


class Question(BaseModel):
    content: str | None = Field(default=None, max_length=2000)
    reply: Reply | None = None
    # The browser's time zone (Intl.DateTimeFormat().resolvedOptions().timeZone): times are told in it.
    tz: str | None = Field(default=None, max_length=64)
    # "深度思考": the model reasons at length first and its reasoning streams; leave out for the server's default.
    thinking: bool | None = None
    # The client's id for this request: sent again, it gets the run it already had (docs/agent-design.md §3.1).
    request_id: str | None = Field(default=None, min_length=8, max_length=64)
    page: Page | None = None


class ProblemError(Exception):
    def __init__(self, status: int, code: str, detail: str, **extra):
        self.status, self.code, self.detail, self.extra = status, code, detail, extra


def create_app(agent: ChatAgent, store: ChatStore) -> FastAPI:
    busy: set[str] = set()

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        yield
        await agent.close()
        store.close()

    app = FastAPI(title="Ops-Agent chat", version="0.1.0", lifespan=lifespan, docs_url="/api/v1/chat/docs",
                  openapi_url="/api/v1/chat/openapi.json")

    @app.exception_handler(ProblemError)
    async def problem(request: Request, e: ProblemError) -> JSONResponse:
        return JSONResponse({"type": "about:blank", "title": e.code.replace("_", " ").capitalize(), "status": e.status,
                             "detail": e.detail, "code": e.code, **e.extra}, status_code=e.status,
                            media_type="application/problem+json")

    def existing(session_id: str) -> dict:
        session = store.session(session_id)
        if session is None:
            raise ProblemError(404, "SESSION_NOT_FOUND", f"No session {session_id}")
        return session

    @app.get("/api/v1/chat/health")
    async def health() -> dict:
        return {"status": "UP", "model": agent.model.name if agent.model else None}

    @app.post("/api/v1/chat/sessions", status_code=201)
    async def create() -> dict:
        return store.create_session()

    @app.get("/api/v1/chat/sessions")
    async def sessions(limit: int = 50) -> dict:
        return {"items": store.sessions(max(1, min(limit, 200)))}

    @app.get("/api/v1/chat/sessions/{session_id}")
    async def session(session_id: str) -> dict:
        found = existing(session_id)
        return {"session_id": found["session_id"], "title": found["title"], "created_at": found["created_at"],
                "updated_at": found["updated_at"], "focus": found["focus"], "refs": found["refs"],
                "messages": store.messages(session_id), "actions": agent.desk.cards(session_id),
                "pause": _shown_pause(store.open_pause(session_id))}

    @app.delete("/api/v1/chat/sessions/{session_id}", status_code=204)
    async def delete(session_id: str) -> Response:
        if session_id in busy:
            raise ProblemError(409, "SESSION_BUSY", "The session is answering a question")
        if not store.delete_session(session_id):
            raise ProblemError(404, "SESSION_NOT_FOUND", f"No session {session_id}")
        return Response(status_code=204)

    @app.post("/api/v1/chat/sessions/{session_id}/messages")
    async def ask(session_id: str, question: Question) -> EventSourceResponse:
        existing(session_id)
        content = (question.content or "").strip()
        if not content and question.reply is None:
            raise ProblemError(422, "VALIDATION_FAILED", "content or reply is required")
        if question.request_id:
            earlier = agent.replay(session_id, question.request_id)
            if earlier is not None:
                if earlier["run"]["status"] == "running":
                    raise ProblemError(409, "RUN_IN_PROGRESS", "This request is still being answered",
                                       run_id=earlier["run"]["run_id"])
                return EventSourceResponse(_replayed(earlier), ping=PING_S,
                                           headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})
        if session_id in busy:
            raise ProblemError(409, "SESSION_BUSY", "The session is still answering the previous question")
        plan = None
        if question.reply is not None:
            try:
                plan = agent.resume_plan(session_id, question.reply.pause_id,
                                         [a.model_dump() for a in question.reply.answers])
            except PauseError as e:
                raise ProblemError(e.status, e.code, e.detail) from e
            content = "\n".join(plan["lines"])
        busy.add(session_id)

        async def events() -> AsyncIterator[dict]:
            try:
                page = question.page.model_dump(exclude_none=True) if question.page else None
                async for event, data in agent.ask(session_id, content, question.tz, question.thinking, plan, page,
                                                   question.request_id):
                    yield {"event": event, "data": json.dumps(data, ensure_ascii=False)}
            except UnknownSession:
                yield {"event": "error", "data": json.dumps({"message": "会话不存在"}, ensure_ascii=False)}
            except DuplicateRequest:
                yield {"event": "error", "data": json.dumps({"message": "这个请求已经在回答了"}, ensure_ascii=False)}
            except asyncio.CancelledError:
                log.info("chat: session %s: the client went away, answer cancelled", session_id)
                raise
            finally:
                busy.discard(session_id)

        # No buffering on the way (proxies), so that each event reaches the browser as it is sent. The background
        # task frees the session also when the stream never started (the client went away first).
        return EventSourceResponse(events(), ping=PING_S, background=BackgroundTask(busy.discard, session_id),
                                   headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})

    @app.get("/api/v1/chat/sessions/{session_id}/runs")
    async def runs(session_id: str, limit: int = 50) -> dict:
        existing(session_id)
        return {"items": store.runs(session_id, max(1, min(limit, 200)))}

    @app.get("/api/v1/chat/runs/{run_id}")
    async def run_chain(run_id: str) -> dict:
        found = await agent.chain(run_id)
        if found is None:
            raise ProblemError(404, "RUN_NOT_FOUND", f"No run {run_id}")
        return found

    @app.get("/api/v1/chat/sessions/{session_id}/actions")
    async def actions(session_id: str) -> dict:
        existing(session_id)
        return {"items": agent.desk.cards(session_id)}

    @app.post("/api/v1/chat/sessions/{session_id}/actions/{action_id}/confirm")
    async def confirm(session_id: str, action_id: str, decided: Decided) -> dict:
        existing(session_id)
        return await _decide(lambda *a: agent.desk.confirm(*a, payload_digest=decided.payload_digest), session_id,
                             action_id, decided.version)

    @app.post("/api/v1/chat/sessions/{session_id}/actions/{action_id}/decline")
    async def decline(session_id: str, action_id: str, decided: Decided) -> dict:
        existing(session_id)
        return await _decide(agent.desk.decline, session_id, action_id, decided.version)

    @app.get("/api/v1/chat/memories")
    async def memories(status: str | None = None, limit: int = 100) -> dict:
        if status not in (None, "candidate", "approved", "rejected"):
            raise ProblemError(400, "VALIDATION_FAILED", "status is candidate, approved or rejected")
        return {"items": store.memories(status, max(1, min(limit, 500)))}

    @app.post("/api/v1/chat/memories/{memory_id}/approve")
    async def approve(memory_id: str, decided: Decided) -> dict:
        return _review(memory_id, decided.version, True)

    @app.post("/api/v1/chat/memories/{memory_id}/reject")
    async def reject(memory_id: str, decided: Decided) -> dict:
        return _review(memory_id, decided.version, False)

    async def _decide(how, session_id: str, action_id: str, version: int) -> dict:
        try:
            return await how(session_id, action_id, version)
        except ActionNotFound:
            raise ProblemError(404, "ACTION_NOT_FOUND", f"No action {action_id} in this session") from None
        except ActionConflict as e:
            raise ProblemError(409, e.code, e.detail, action=e.action) from None

    def _review(memory_id: str, version: int, approve: bool) -> dict:
        memory = agent.knowledge.review(memory_id, version, approve)
        if memory is None:
            raise ProblemError(404, "MEMORY_NOT_FOUND", f"No memory {memory_id}")
        wanted = "approved" if approve else "rejected"
        if memory["status"] != wanted or memory["version"] != version + 1:
            raise ProblemError(409, "MEMORY_CHANGED", f"这条记忆现在是 {memory['status']}，请刷新后再审核",
                               memory=memory)
        return memory

    return app


async def _replayed(earlier: dict) -> AsyncIterator[dict]:
    """The answer a repeated request already got, as the events it was streamed with, ending with done."""
    run, message = earlier["run"], earlier["message"] or {}
    trace = message.get("trace") or {}

    def event(name: str, data: dict) -> dict:
        return {"event": name, "data": json.dumps(data, ensure_ascii=False)}

    yield event("meta", {"message_id": run["message_id"], "session_id": run["session_id"], "replayed": True})
    if trace.get("route"):
        yield event("route", trace["route"])
    for item in trace.get("items") or []:
        if item.get("type") == "step":
            yield event("step", {k: v for k, v in item.items() if k != "type"})
    if message.get("content"):
        yield event("answer", {"delta": message["content"]})
    for card in trace.get("actions") or []:
        yield event("action", card)
    if trace.get("questionnaire"):
        yield event("questionnaire", trace["questionnaire"])
    yield event("citations", {"items": trace.get("citations") or []})
    yield event("done", {"message_id": run["message_id"], "status": message.get("status", run["status"]),
                         "run_id": run["run_id"], "run_status": run["status"], "case_id": run["case_id"],
                         "replayed": True, **(trace.get("metrics") or {})})


def _shown_pause(pause: dict | None) -> dict | None:
    if pause is None:
        return None
    return {"pause_id": pause["pause_id"], "message_id": pause["message_id"], "source": pause["state"]["source"],
            "questions": pause["questions"], "expires_at": pause["expires_at"]}
