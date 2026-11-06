"""What the assistant knows across sessions (docs/project-design.md §6.7, 跨会话记忆).

The session's own memory (focus, recent turns, summary) says where this conversation is. This is the other kind:
experience worth having in any session, such as "order-service's Redis alert at start-up is known noise" or "when
shop-mysql is down, order-service logs Communications link failure first".

Nothing gets in by itself. The model may propose a memory with propose_memory, citing evidence from this session;
it is kept as a candidate, and only a person's approve (at the version they saw, a compare-and-set) makes it one the
assistant is given. The reason is where the evidence comes from: logs and alerts are written by the watched system,
so text in them could otherwise become a standing instruction to every later session. Candidates that rest on log
lines, or that read like instructions, are flagged for the reviewer. Approved memories are given to the model as
reference, never as the present state, and only those about the components in question.
"""

import re
import uuid
from datetime import datetime, timezone

from .evidence import RefTable
from .memory import ChatStore

MAX_TEXT = 200
MAX_COMPONENTS = 3
MAX_EVIDENCE = 6
RELEVANT = 5
INSTRUCTION_LIKE = re.compile(r"忽略|无视|不要理会|以后(都|一律)|总是回答|必须回答|直接回答|系统提示|指令|提示词|"
                              r"ignore|disregard|always answer|you must|system prompt|instruction", re.IGNORECASE)


def tool_spec() -> dict:
    return {"type": "function", "function": {
        "name": "propose_memory",
        "description": ("把一条以后其他对话也用得上的运维经验记为候选（需要人工审核后才生效）。只记有证据支持、"
                        "不随时间变化的经验，例如某条告警是已知噪音、某个组件故障时的典型日志；不要记某个 Incident "
                        "的当前状态。"),
        "parameters": {"type": "object", "additionalProperties": False, "required": ["text", "components", "evidence"],
                       "properties": {
                           "text": {"type": "string", "description": f"经验本身，一两句话，不超过 {MAX_TEXT} 字"},
                           "components": {"type": "array", "items": {"type": "string"}, "minItems": 1,
                                          "maxItems": MAX_COMPONENTS, "description": "涉及的组件，用服务目录里的名字"},
                           "evidence": {"type": "array", "items": {"type": "string"}, "minItems": 1,
                                        "maxItems": MAX_EVIDENCE, "description": "支持它的编号，如 [\"A1\", \"E2\"]"}}}}}


class Knowledge:
    def __init__(self, store: ChatStore):
        self._store = store

    def propose(self, session_id: str, message_id: str, refs: RefTable, catalog: set[str], args: dict) -> dict:
        text = " ".join(str(args.get("text") or "").split())
        if not 10 <= len(text) <= MAX_TEXT:
            raise ValueError(f"text 要在 10 到 {MAX_TEXT} 字之间")
        components = [str(c).strip() for c in args.get("components") or []]
        if not 1 <= len(components) <= MAX_COMPONENTS:
            raise ValueError(f"components 要有 1 到 {MAX_COMPONENTS} 个")
        unknown = [c for c in components if c not in catalog]
        if unknown:
            raise ValueError(f"服务目录里没有 {'、'.join(unknown)}")
        given = args.get("evidence")
        if not isinstance(given, list) or not 1 <= len(given) <= MAX_EVIDENCE:
            raise ValueError(f"evidence 要有 1 到 {MAX_EVIDENCE} 个编号")
        evidence = []
        for value in dict.fromkeys(str(v).strip().upper() for v in given):
            item = refs.resolve(value)
            if item is None:
                raise ValueError(f"{value} 不在查到的数据里")
            evidence.append({"ref": item.ref, "kind": item.kind, "id": item.id, "label": item.label,
                             "incident_id": item.incident_id})
        for existing in self._store.memories(limit=500):
            if existing["status"] in ("candidate", "approved") and existing["text"] == text:
                return existing
        flags = []
        if any(e["kind"] == "event" for e in evidence):
            flags.append("from_logs")
        if INSTRUCTION_LIKE.search(text):
            flags.append("instruction_like")
        memory = {"memory_id": str(uuid.uuid4()), "text": text, "components": components, "evidence": evidence,
                  "flags": flags, "source_session": session_id, "source_message": message_id,
                  "created_at": datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")}
        self._store.add_memory(memory)
        return self._store.memory(memory["memory_id"])

    def relevant(self, components: set[str], limit: int = RELEVANT) -> list[dict]:
        """Approved memories about any of these components, the newest first. Candidates never."""
        if not components:
            return []
        return [m for m in self._store.memories("approved", limit=200) if set(m["components"]) & components][:limit]

    def review(self, memory_id: str, version: int, approve: bool) -> dict | None:
        """None: no such memory. Unchanged when it is not a candidate at that version any more."""
        if self._store.memory(memory_id) is None:
            return None
        self._store.review_memory(memory_id, version, "approved" if approve else "rejected")
        return self._store.memory(memory_id)
