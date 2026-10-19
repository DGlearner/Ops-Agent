"""The chat's HTTP API (FastAPI), on 127.0.0.1:8083 by default; the console's dev server forwards /api/v1/chat here.

    POST   /api/v1/chat/sessions                  a new session
    GET    /api/v1/chat/sessions                  the latest sessions
    GET    /api/v1/chat/sessions/{id}             one session with its messages and their traces
    DELETE /api/v1/chat/sessions/{id}
    POST   /api/v1/chat/sessions/{id}/messages    ask; the answer comes back as Server-Sent Events
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

from .agent import ChatAgent, UnknownSession
from .memory import ChatStore

log = logging.getLogger(__name__)

PING_S = 15


class Question(BaseModel):
    content: str = Field(min_length=1, max_length=2000)
    # The browser's time zone (Intl.DateTimeFormat().resolvedOptions().timeZone): times are told in it.
    tz: str | None = Field(default=None, max_length=64)
    # "深度思考": the model reasons at length first and its reasoning streams; leave out for the server's default.
    thinking: bool | None = None


class ProblemError(Exception):
    def __init__(self, status: int, code: str, detail: str):
        self.status, self.code, self.detail = status, code, detail


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
                             "detail": e.detail, "code": e.code}, status_code=e.status,
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
                "messages": store.messages(session_id)}

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
        if session_id in busy:
            raise ProblemError(409, "SESSION_BUSY", "The session is still answering the previous question")
        busy.add(session_id)

        async def events() -> AsyncIterator[dict]:
            try:
                async for event, data in agent.ask(session_id, question.content.strip(), question.tz,
                                                   question.thinking):
                    yield {"event": event, "data": json.dumps(data, ensure_ascii=False)}
            except UnknownSession:
                yield {"event": "error", "data": json.dumps({"message": "会话不存在"}, ensure_ascii=False)}
            except asyncio.CancelledError:
                log.info("chat: session %s: the client went away, answer cancelled", session_id)
                raise
            finally:
                busy.discard(session_id)

        # No buffering on the way (proxies), so that each event reaches the browser as it is sent. The background
        # task frees the session also when the stream never started (the client went away first).
        return EventSourceResponse(events(), ping=PING_S, background=BackgroundTask(busy.discard, session_id),
                                   headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})

    return app
