"""Short, stable names for what the model may cite. The model sees I1 for an incident, A3 for an alert, E2 for a
sample log line instead of their UUIDs: they cost fewer tokens, cannot be misquoted halfway, and keep the same name
for the whole session, so a ref in an earlier answer still means the same thing. Every ref in an answer is checked
against the table; the console marks the ones that are not in it."""

import re
from dataclasses import asdict, dataclass
from typing import Literal

Kind = Literal["incident", "alert", "event"]
PREFIX: dict[str, Kind] = {"I": "incident", "A": "alert", "E": "event"}
_PREFIX_OF = {kind: prefix for prefix, kind in PREFIX.items()}
KIND_LABEL = {"incident": "Incident", "alert": "告警", "event": "日志"}

# [A1] or [A1, E2]; the model also writes Chinese commas.
CITATION = re.compile(r"\[\s*([AIE]\d{1,4}(?:\s*[,，、]\s*[AIE]\d{1,4})*)\s*\]")
REF = re.compile(r"[AIE]\d{1,4}")


@dataclass(frozen=True)
class RefItem:
    ref: str
    kind: Kind
    id: str
    label: str
    # The incident an alert or a sample belongs to, when it is known: the console links there.
    incident_id: str | None = None


class RefTable:
    def __init__(self, items: dict[str, dict] | None = None):
        self._items: dict[str, RefItem] = {ref: RefItem(**item) for ref, item in (items or {}).items()}
        self._by_id: dict[tuple[str, str], str] = {(i.kind, i.id.lower()): ref for ref, i in self._items.items()}

    def ref(self, kind: Kind, id: str, label: str, incident_id: str | None = None) -> str:
        """The ref of this item, given a new one the first time it is seen."""
        key = (kind, id.lower())
        if key in self._by_id:
            existing = self._items[self._by_id[key]]
            if incident_id and not existing.incident_id:
                self._items[existing.ref] = RefItem(existing.ref, kind, existing.id, existing.label, incident_id)
            return existing.ref
        prefix = _PREFIX_OF[kind]
        n = 1 + sum(1 for r in self._items if r.startswith(prefix))
        ref = f"{prefix}{n}"
        self._items[ref] = RefItem(ref, kind, id, label[:160], incident_id)
        self._by_id[key] = ref
        return ref

    def resolve(self, ref: str) -> RefItem | None:
        return self._items.get(ref.strip().upper())

    def id_of(self, value: str, kind: Kind) -> str:
        """A tool argument may be a ref or the id itself."""
        item = self.resolve(value)
        if item is not None:
            if item.kind != kind:
                raise ValueError(f"{value} 不是{KIND_LABEL[kind]}")
            return item.id
        return value.strip()

    def as_json(self) -> dict[str, dict]:
        return {ref: asdict(item) for ref, item in self._items.items()}

    def __len__(self) -> int:
        return len(self._items)


def cited(text: str) -> list[str]:
    """The refs an answer cites, in order, each once."""
    refs: list[str] = []
    for group in CITATION.finditer(text):
        for ref in REF.findall(group.group(1)):
            if ref not in refs:
                refs.append(ref)
    return refs


def citations(text: str, table: RefTable) -> list[dict]:
    """Each cited ref with what it stands for, or verified false when the table does not have it."""
    result = []
    for ref in cited(text):
        item = table.resolve(ref)
        result.append({"ref": ref, "verified": False} if item is None else {**asdict(item), "verified": True})
    return result
