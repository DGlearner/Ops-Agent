"""Sessions and what the assistant remembers of them, in SQLite (agent/data/chat.sqlite3, one file, nothing to run).

Three layers, each cheaper to carry than the one before:
- the focus: what the conversation is about right now (the incidents last discussed, services, time window), a few
  dozen bytes, read by the router and the orchestration to resolve "它" and "这个故障";
- the last turns word for word (chat_history_turns);
- a summary of everything older, written by the quick model once there is enough to fold in.
Also kept per session: the refs (evidence.RefTable), so that I1 means the same incident all along.

Besides the messages, the same file keeps the conversation's state between turns, every change of it a
compare-and-set on a status (and a version where a person decides on what they saw):
- action: what the assistant proposed and only the user's confirm runs (chat.actions), with its receipt;
- pause: a questionnaire the assistant is waiting on, and what it needs to go on from there (chat.clarify);
- memory: what is worth knowing in other sessions too (chat.knowledge): a candidate until someone approves it.
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
CREATE TABLE IF NOT EXISTS action (
    action_id TEXT PRIMARY KEY,
    session_id TEXT NOT NULL REFERENCES session (session_id) ON DELETE CASCADE,
    message_id TEXT NOT NULL,
    kind TEXT NOT NULL,
    -- proposed, executing; then succeeded, failed, blocked, declined, expired or unknown
    status TEXT NOT NULL,
    -- +1 on every change: a confirm names the version the user saw
    version INTEGER NOT NULL DEFAULT 1,
    -- title, params, target (the incident, its status and version when proposed), evidence, reason
    card TEXT NOT NULL,
    receipt TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    expires_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS action_session ON action (session_id, created_at);
CREATE TABLE IF NOT EXISTS pause (
    pause_id TEXT PRIMARY KEY,
    session_id TEXT NOT NULL REFERENCES session (session_id) ON DELETE CASCADE,
    message_id TEXT NOT NULL,
    -- open; then answered, superseded (the user asked something else) or expired
    status TEXT NOT NULL,
    questions TEXT NOT NULL,
    -- what the answer goes on with: the question, its route and entities
    state TEXT NOT NULL,
    answer TEXT,
    created_at TEXT NOT NULL,
    expires_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS pause_session ON pause (session_id, status);
CREATE TABLE IF NOT EXISTS memory (
    memory_id TEXT PRIMARY KEY,
    -- candidate; then approved or rejected, by a person
    status TEXT NOT NULL,
    version INTEGER NOT NULL DEFAULT 1,
    text TEXT NOT NULL,
    components TEXT NOT NULL,
    evidence TEXT NOT NULL,
    -- why a reviewer should look twice: from_logs, instruction_like
    flags TEXT NOT NULL,
    -- no foreign key: an approved memory outlives the session it came from
    source_session TEXT NOT NULL,
    source_message TEXT NOT NULL,
    created_at TEXT NOT NULL,
    reviewed_at TEXT
);
CREATE INDEX IF NOT EXISTS memory_status ON memory (status, created_at);
CREATE TABLE IF NOT EXISTS run (
    run_id TEXT PRIMARY KEY,
    session_id TEXT NOT NULL REFERENCES session (session_id) ON DELETE CASCADE,
    -- the client's id for the request: the same request sent again gets this run, not a second answer
    request_id TEXT,
    -- user_message, questionnaire_reply
    trigger TEXT NOT NULL,
    -- running; then answered, paused_question, paused_action, degraded, failed or cancelled
    status TEXT NOT NULL,
    message_id TEXT NOT NULL,
    case_id TEXT,
    -- what the model was given (docs/agent-design.md §3.3), and its digest
    snapshot TEXT,
    snapshot_digest TEXT,
    -- what it spent and where the time went (chat.usage)
    usage TEXT,
    started_at TEXT NOT NULL,
    ended_at TEXT,
    UNIQUE (session_id, request_id)
);
CREATE INDEX IF NOT EXISTS run_session ON run (session_id, started_at);
"""
TERMINAL_ACTION = ("succeeded", "failed", "blocked", "declined", "expired", "unknown")
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
            if not any(r["name"] == "usage" for r in self._db.execute("PRAGMA table_info(run)")):
                self._db.execute("ALTER TABLE run ADD COLUMN usage TEXT")  # a file from before usage was kept
            self._db.execute("CREATE INDEX IF NOT EXISTS run_message ON run (message_id)")
            self._db.execute("UPDATE run SET status = 'cancelled', ended_at = ? WHERE status = 'running'", (_now(),))
            # An action left executing by a process that stopped: whether it took effect is not known here.
            self._db.execute("UPDATE action SET status = 'unknown', version = version + 1, updated_at = ?, "
                             "receipt = ? WHERE status = 'executing'",
                             (_now(), json.dumps({"status": "unknown", "executed": None, "at": _now(),
                                                  "summary": "执行过程中服务停止了，不知道是否已生效；请在 Incident 详情里核对"},
                                                 ensure_ascii=False)))

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

    def save_summary(self, session_id: str, summary: str, through: int, expected_through: int) -> bool:
        """Compare-and-set: the summary is written only if nobody folded messages in since it was started from
        expected_through. A summary that loses the race is dropped, never written over the newer one: otherwise a
        slow summary of turns 1-8 could land after one of 1-12 and take summarized_through back."""
        with self._lock:
            return self._db.execute(
                "UPDATE session SET summary = ?, summarized_through = ?, updated_at = ? "
                "WHERE session_id = ? AND summarized_through = ?",
                (summary, through, _now(), session_id, expected_through)).rowcount == 1

    # Actions (chat.actions).

    def add_action(self, action: dict) -> None:
        with self._lock:
            self._db.execute("INSERT INTO action (action_id, session_id, message_id, kind, status, version, card, "
                             "created_at, updated_at, expires_at) VALUES (?, ?, ?, ?, 'proposed', 1, ?, ?, ?, ?)",
                             (action["action_id"], action["session_id"], action["message_id"], action["kind"],
                              _json(action["card"]), action["created_at"], action["created_at"],
                              action["expires_at"]))

    def action(self, action_id: str) -> dict | None:
        with self._lock:
            row = self._db.execute("SELECT * FROM action WHERE action_id = ?", (action_id,)).fetchone()
        return None if row is None else _action(row)

    def actions(self, session_id: str) -> list[dict]:
        with self._lock:
            rows = self._db.execute("SELECT * FROM action WHERE session_id = ? ORDER BY created_at",
                                    (session_id,)).fetchall()
        return [_action(r) for r in rows]

    def move_action(self, action_id: str, from_status: str, from_version: int, to_status: str,
                    receipt: dict | None = None) -> bool:
        """Compare-and-set: from_status at from_version to to_status, version + 1. False: it was not there any
        more (another confirm, a decline, an expiry got there first), and nothing changed."""
        with self._lock:
            return self._db.execute(
                "UPDATE action SET status = ?, version = version + 1, receipt = COALESCE(?, receipt), updated_at = ? "
                "WHERE action_id = ? AND status = ? AND version = ?",
                (to_status, None if receipt is None else _json(receipt), _now(), action_id, from_status,
                 from_version)).rowcount == 1

    # Pauses (chat.clarify).

    def add_pause(self, pause: dict) -> None:
        with self._lock:
            # A new questionnaire replaces one still open: only the latest is answered.
            self._db.execute("UPDATE pause SET status = 'superseded' WHERE session_id = ? AND status = 'open'",
                             (pause["session_id"],))
            self._db.execute("INSERT INTO pause (pause_id, session_id, message_id, status, questions, state, "
                             "created_at, expires_at) VALUES (?, ?, ?, 'open', ?, ?, ?, ?)",
                             (pause["pause_id"], pause["session_id"], pause["message_id"], _json(pause["questions"]),
                              _json(pause["state"]), pause["created_at"], pause["expires_at"]))

    def pause(self, pause_id: str) -> dict | None:
        with self._lock:
            row = self._db.execute("SELECT * FROM pause WHERE pause_id = ?", (pause_id,)).fetchone()
        return None if row is None else _pause(row)

    def open_pause(self, session_id: str) -> dict | None:
        with self._lock:
            row = self._db.execute("SELECT * FROM pause WHERE session_id = ? AND status = 'open' "
                                   "ORDER BY created_at DESC LIMIT 1", (session_id,)).fetchone()
        return None if row is None else _pause(row)

    def close_pause(self, pause_id: str, to_status: str, answer: dict | None = None) -> bool:
        """Compare-and-set from open: one answer per questionnaire, however often it is sent."""
        with self._lock:
            return self._db.execute("UPDATE pause SET status = ?, answer = ? WHERE pause_id = ? AND status = 'open'",
                                    (to_status, None if answer is None else _json(answer), pause_id)).rowcount == 1

    # Memories across sessions (chat.knowledge).

    def add_run(self, run_id: str, session_id: str, request_id: str | None, trigger: str, message_id: str) -> bool:
        """False if this session already has a run for request_id."""
        with self._lock:
            try:
                self._db.execute("INSERT INTO run (run_id, session_id, request_id, trigger, status, message_id, "
                                 "started_at) VALUES (?, ?, ?, ?, 'running', ?, ?)",
                                 (run_id, session_id, request_id, trigger, message_id, _now()))
            except sqlite3.IntegrityError:
                return False
        return True

    def finish_run(self, run_id: str, status: str, case_id: str | None, snapshot: dict | None,
                   digest: str | None, usage: dict | None = None) -> None:
        with self._lock:
            self._db.execute("UPDATE run SET status = ?, case_id = ?, snapshot = ?, snapshot_digest = ?, usage = ?, "
                             "ended_at = ? WHERE run_id = ? AND status = 'running'",
                             (status, case_id, None if snapshot is None else _json(snapshot), digest,
                              None if usage is None else _json(usage), _now(), run_id))

    def run(self, run_id: str) -> dict | None:
        with self._lock:
            row = self._db.execute("SELECT * FROM run WHERE run_id = ?", (run_id,)).fetchone()
        return None if row is None else _run(row)

    def run_of_message(self, message_id: str) -> dict | None:
        """The run that wrote this answer: an action knows its message, and so its run."""
        with self._lock:
            row = self._db.execute("SELECT * FROM run WHERE message_id = ?", (message_id,)).fetchone()
        return None if row is None else _run(row)

    def run_for(self, session_id: str, request_id: str) -> dict | None:
        with self._lock:
            row = self._db.execute("SELECT * FROM run WHERE session_id = ? AND request_id = ?",
                                   (session_id, request_id)).fetchone()
        return None if row is None else _run(row)

    def runs(self, session_id: str, limit: int = 50) -> list[dict]:
        with self._lock:
            rows = self._db.execute("SELECT * FROM run WHERE session_id = ? ORDER BY started_at DESC LIMIT ?",
                                    (session_id, limit)).fetchall()
        return [_run(r) for r in rows]

    def message(self, message_id: str) -> dict | None:
        with self._lock:
            row = self._db.execute("SELECT * FROM message WHERE message_id = ?", (message_id,)).fetchone()
        return None if row is None else _message(row)

    def add_memory(self, memory: dict) -> None:
        with self._lock:
            self._db.execute("INSERT INTO memory (memory_id, status, version, text, components, evidence, flags, "
                             "source_session, source_message, created_at) "
                             "VALUES (?, 'candidate', 1, ?, ?, ?, ?, ?, ?, ?)",
                             (memory["memory_id"], memory["text"], _json(memory["components"]),
                              _json(memory["evidence"]), _json(memory["flags"]), memory["source_session"],
                              memory["source_message"], memory["created_at"]))

    def memory(self, memory_id: str) -> dict | None:
        with self._lock:
            row = self._db.execute("SELECT * FROM memory WHERE memory_id = ?", (memory_id,)).fetchone()
        return None if row is None else _memory(row)

    def memories(self, status: str | None = None, limit: int = 100) -> list[dict]:
        with self._lock:
            rows = self._db.execute("SELECT * FROM memory WHERE ? IS NULL OR status = ? ORDER BY created_at DESC "
                                    "LIMIT ?", (status, status, limit)).fetchall()
        return [_memory(r) for r in rows]

    def review_memory(self, memory_id: str, version: int, to_status: str) -> bool:
        """Compare-and-set from candidate at the version the reviewer saw."""
        with self._lock:
            return self._db.execute(
                "UPDATE memory SET status = ?, version = version + 1, reviewed_at = ? "
                "WHERE memory_id = ? AND status = 'candidate' AND version = ?",
                (to_status, _now(), memory_id, version)).rowcount == 1

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


def _action(row: sqlite3.Row) -> dict:
    return {"action_id": row["action_id"], "session_id": row["session_id"], "message_id": row["message_id"],
            "kind": row["kind"], "status": row["status"], "version": row["version"], **json.loads(row["card"]),
            "receipt": None if row["receipt"] is None else json.loads(row["receipt"]),
            "created_at": row["created_at"], "updated_at": row["updated_at"], "expires_at": row["expires_at"]}


def _run(row: sqlite3.Row) -> dict:
    return {k: row[k] for k in ("run_id", "session_id", "request_id", "trigger", "status", "message_id", "case_id",
                                "snapshot_digest", "started_at", "ended_at")} | {
        "snapshot": None if row["snapshot"] is None else json.loads(row["snapshot"]),
        "usage": None if row["usage"] is None else json.loads(row["usage"])}


def _pause(row: sqlite3.Row) -> dict:
    return {"pause_id": row["pause_id"], "session_id": row["session_id"], "message_id": row["message_id"],
            "status": row["status"], "questions": json.loads(row["questions"]), "state": json.loads(row["state"]),
            "answer": None if row["answer"] is None else json.loads(row["answer"]), "created_at": row["created_at"],
            "expires_at": row["expires_at"]}


def _memory(row: sqlite3.Row) -> dict:
    return {"memory_id": row["memory_id"], "status": row["status"], "version": row["version"], "text": row["text"],
            "components": json.loads(row["components"]), "evidence": json.loads(row["evidence"]),
            "flags": json.loads(row["flags"]), "source_session": row["source_session"],
            "source_message": row["source_message"], "created_at": row["created_at"],
            "reviewed_at": row["reviewed_at"]}


def _json(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False)


def _now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")
