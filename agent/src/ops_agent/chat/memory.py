"""Sessions and what the assistant remembers of them, in SQLite (agent/data/chat.sqlite3, one file, nothing to run).

Three layers, each cheaper to carry than the one before:
- the focus: what the conversation is about right now (the incidents last discussed, services, time window), a few
  dozen bytes, read by the router and the orchestration to resolve "它" and "这个故障";
- the last turns word for word (chat_history_turns);
- a summary of everything older, written by the quick model once there is enough to fold in.
Also kept per session: the refs (evidence.RefTable), so that I1 means the same incident all along.
"""

import json
import sqlite3
import threading
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

SCHEMA = """
CREATE TABLE IF NOT EXISTS session (
    session_id TEXT PRIMARY KEY,
    title TEXT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    focus TEXT NOT NULL DEFAULT '{}',
    refs TEXT NOT NULL DEFAULT '{}',
    summary TEXT,
    -- messages up to this seq are in the summary
    summarized_through INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS message (
    message_id TEXT PRIMARY KEY,
    session_id TEXT NOT NULL REFERENCES session (session_id) ON DELETE CASCADE,
    seq INTEGER NOT NULL,
    role TEXT NOT NULL,
    content TEXT NOT NULL,
    -- completed, failed, cancelled
    status TEXT NOT NULL,
    -- the assistant's route, steps, thinking, citations and timings, as streamed
    trace TEXT,
    created_at TEXT NOT NULL,
    UNIQUE (session_id, seq)
);
"""
NEW_TITLE = "新对话"


class ChatStore:
    def __init__(self, path: Path | str):
        if str(path) != ":memory:":
            Path(path).parent.mkdir(parents=True, exist_ok=True)
        self._db = sqlite3.connect(str(path), check_same_thread=False, isolation_level=None)
        self._db.row_factory = sqlite3.Row
        self._lock = threading.Lock()
        with self._lock:
            self._db.execute("PRAGMA foreign_keys = ON")
            self._db.execute("PRAGMA journal_mode = WAL")
            self._db.executescript(SCHEMA)

    def create_session(self) -> dict:
        now = _now()
        session_id = str(uuid.uuid4())
        with self._lock:
            self._db.execute("INSERT INTO session (session_id, title, created_at, updated_at) VALUES (?, ?, ?, ?)",
                             (session_id, NEW_TITLE, now, now))
        return self.session(session_id)

    def sessions(self, limit: int = 50) -> list[dict]:
        with self._lock:
            rows = self._db.execute("SELECT * FROM session ORDER BY updated_at DESC LIMIT ?", (limit,)).fetchall()
        return [_session(r, brief=True) for r in rows]

    def session(self, session_id: str) -> dict | None:
        with self._lock:
            row = self._db.execute("SELECT * FROM session WHERE session_id = ?", (session_id,)).fetchone()
        return None if row is None else _session(row)

    def delete_session(self, session_id: str) -> bool:
        with self._lock:
            return self._db.execute("DELETE FROM session WHERE session_id = ?", (session_id,)).rowcount > 0

    def messages(self, session_id: str, after_seq: int = 0) -> list[dict]:
        with self._lock:
            rows = self._db.execute("SELECT * FROM message WHERE session_id = ? AND seq > ? ORDER BY seq",
                                    (session_id, after_seq)).fetchall()
        return [_message(r) for r in rows]

    def add_message(self, session_id: str, role: str, content: str, status: str = "completed",
                    trace: dict | None = None, message_id: str | None = None) -> dict:
        now = _now()
        message_id = message_id or str(uuid.uuid4())
        with self._lock:
            seq = self._db.execute("SELECT COALESCE(MAX(seq), 0) + 1 FROM message WHERE session_id = ?",
                                   (session_id,)).fetchone()[0]
            self._db.execute("INSERT INTO message (message_id, session_id, seq, role, content, status, trace, "
                             "created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                             (message_id, session_id, seq, role, content, status,
                              None if trace is None else json.dumps(trace, ensure_ascii=False), now))
            self._db.execute("UPDATE session SET updated_at = ? WHERE session_id = ?", (now, session_id))
        return {"message_id": message_id, "seq": seq, "role": role, "content": content, "status": status,
                "trace": trace, "created_at": now}

    def update_session(self, session_id: str, **fields: Any) -> None:
        allowed = {"title", "focus", "refs", "summary", "summarized_through"}
        if not fields or not set(fields) <= allowed:
            raise ValueError(f"cannot update {set(fields) - allowed}")
        values = [json.dumps(v, ensure_ascii=False) if k in ("focus", "refs") else v for k, v in fields.items()]
        assignments = ", ".join(f"{k} = ?" for k in fields)
        with self._lock:
            self._db.execute(f"UPDATE session SET {assignments}, updated_at = ? WHERE session_id = ?",  # noqa: S608
                             (*values, _now(), session_id))

    def close(self) -> None:
        with self._lock:
            self._db.close()


class Memory:
    """What the model is given of a session before a new question."""

    def __init__(self, store: ChatStore, turns: int = 3, summarize_after: int = 4):
        self._store = store
        self.turns = turns
        # Summarize once this many turns beyond the recent ones have piled up, not after every turn.
        self._summarize_after = summarize_after

    def recall(self, session: dict) -> dict:
        messages = [m for m in self._store.messages(session["session_id"]) if m["status"] != "failed"]
        recent = messages[-2 * self.turns:] if self.turns else []
        return {"summary": session.get("summary"), "focus": session.get("focus") or {},
                "history": [{"role": m["role"], "content": _clip(m["content"])} for m in recent if m["content"]]}

    def due_for_summary(self, session: dict) -> list[dict]:
        """The messages to fold into the summary now, or nothing yet."""
        messages = self._store.messages(session["session_id"], session.get("summarized_through", 0))
        older = messages[:-2 * self.turns] if self.turns else messages
        return older if len(older) >= 2 * self._summarize_after else []


def _clip(text: str, limit: int = 1500) -> str:
    return text if len(text) <= limit else text[:limit] + "…"


def _session(row: sqlite3.Row, brief: bool = False) -> dict:
    session = {"session_id": row["session_id"], "title": row["title"], "created_at": row["created_at"],
               "updated_at": row["updated_at"]}
    if not brief:
        session.update(focus=json.loads(row["focus"]), refs=json.loads(row["refs"]), summary=row["summary"],
                       summarized_through=row["summarized_through"])
    return session


def _message(row: sqlite3.Row) -> dict:
    return {"message_id": row["message_id"], "seq": row["seq"], "role": row["role"], "content": row["content"],
            "status": row["status"], "trace": None if row["trace"] is None else json.loads(row["trace"]),
            "created_at": row["created_at"]}


def _now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")
