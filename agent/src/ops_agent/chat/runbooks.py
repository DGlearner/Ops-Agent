"""Runbooks: how to diagnose and handle one kind of fault (docs/agent-design.md §4, §13.4). They are YAML in
ops_agent/runbooks, written and reviewed by people; nothing the model says becomes a runbook step.

A runbook is matched to an incident by the components the rules point at and by its signals in what was fetched
(alert summaries, samples, the services' own warnings and errors). Its probes are run by the orchestration, not the
model: the lab's container states, MySQL's long transactions, order-service's switches. Each result becomes an
observation with a ref (P1, P2), written to the case as a fact. A remediation may only be proposed when the runbook
lists it and its requires hold on those observations: the runbook gives the method, the code holds the line.

Signals depend on what has reached the logs by now, what the lab looks like does not: a runbook may defer to another
when a probe sees that the other one is the fault (deferred).
"""

import asyncio
import logging
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import yaml

from ..core_api import CoreApi, CoreApiError

log = logging.getLogger(__name__)

RUNBOOK_DIR = Path(__file__).resolve().parent.parent / "runbooks"
SIGNAL_WEIGHT = 2


@dataclass(frozen=True)
class Runbook:
    id: str
    version: int
    title: str
    components: tuple[str, ...]
    signals: tuple[str, ...]
    probes: tuple[dict, ...]
    look_at: tuple[str, ...]
    remediations: tuple[dict, ...]
    escalate: tuple[dict, ...]
    # {probe, to, says}: when this probe finds anything, the fault is the other runbook's.
    defer: tuple[dict, ...] = ()

    def remediation(self, op: str) -> dict | None:
        return next((r for r in self.remediations if r["op"] == op), None)

    def brief(self) -> dict:
        return {"id": self.id, "version": self.version, "title": self.title, "look_at": list(self.look_at),
                "remediations": [{"op": r["op"], "title": r["title"], "effect": r["effect"]} for r in self.remediations],
                "escalate": [f"{e['when']} → {e['to']}" for e in self.escalate]}


@dataclass
class Observation:
    """A probe's result: what the lab looked like, with a ref the model and the cards cite."""
    probe: str
    title: str
    ok: bool
    value: Any
    text: str
    ref: str = ""
    error: str | None = None


@dataclass
class Match:
    runbook: Runbook
    score: int
    hits: list[str] = field(default_factory=list)


def load(directory: Path = RUNBOOK_DIR) -> list[Runbook]:
    books = []
    for path in sorted(directory.glob("*.yaml")):
        raw = yaml.safe_load(path.read_text(encoding="utf-8"))
        books.append(Runbook(id=raw["id"], version=int(raw["version"]), title=raw["title"],
                             components=tuple(raw["match"].get("components", [])),
                             signals=tuple(raw["match"].get("signals", [])), probes=tuple(raw.get("probes", [])),
                             look_at=tuple(raw.get("look_at", [])), remediations=tuple(raw.get("remediations", [])),
                             escalate=tuple(raw.get("escalate", [])), defer=tuple(raw.get("defer", []))))
    return books


def match(books: list[Runbook], pointed: list[str], texts: list[str]) -> Match | None:
    """The runbook that fits best, if any: a signal seen in the data is needed, the rules' pointing alone is not."""
    corpus = "\n".join(texts).lower()
    best: Match | None = None
    for book in books:
        hits = [s for s in book.signals if s.lower() in corpus]
        if not hits:
            continue
        score = SIGNAL_WEIGHT * len(hits) + sum(1 for c in book.components if c in pointed)
        if best is None or score > best.score:
            best = Match(book, score, hits)
    return best


def deferred(book: Runbook, observations: list[Observation], books: list[Runbook]) -> tuple[Runbook, str] | None:
    """The runbook this one hands over to, from what its probes saw, and why. A probe that failed says nothing."""
    for rule in book.defer:
        seen = next((o for o in observations if o.probe == rule["probe"]), None)
        target = next((b for b in books if b.id == rule["to"]), None)
        if seen is not None and seen.ok and seen.value and target is not None:
            return target, rule["says"]
    return None


def _waiting(transaction: dict) -> bool:
    return transaction.get("state") == "LOCK WAIT"


def _transaction(t: dict) -> str:
    # blocking is absent when MySQL runs without Performance Schema: unknown, which is not the same as none.
    blocks = "阻塞数未知" if t.get("blocking") is None else f"阻塞 {t['blocking']} 个"
    state = "正在等锁" if _waiting(t) else (t.get("state") or "状态未知")
    return f"事务 {t['trx_id']}（线程 {t['thread_id']}，{t['age_seconds']} 秒，{state}，{blocks}，{t.get('user') or '?'}）"


async def probe(book: Runbook, core: CoreApi) -> list[Observation]:
    async def one(spec: dict) -> Observation:
        try:
            if spec["kind"] == "container":
                got = await asyncio.to_thread(core.lab_container, spec["name"])
                return Observation(spec["id"], spec["title"], True, {"state": got["state"]},
                                   f"{spec['name']} 容器：{got['state']}")
            if spec["kind"] == "transactions":
                rows = await asyncio.to_thread(core.lab_transactions, int(spec.get("min_age_s", 10)))
                if spec.get("waiting_only"):
                    rows = [t for t in rows if _waiting(t)]
                text = "没有" if not rows else "；".join(_transaction(t) for t in rows[:3])
                return Observation(spec["id"], spec["title"], True, rows, f"持续 {spec.get('min_age_s', 10)} 秒以上的事务："
                                   + text)
            if spec["kind"] == "switches":
                rows = await asyncio.to_thread(core.lab_switches)
                value = {s["name"]: bool(s["on"]) for s in rows}
                return Observation(spec["id"], spec["title"], True, value, "降级开关：" + "、".join(
                    f"{k} {'开' if v else '关'}" for k, v in sorted(value.items())))
            raise ValueError(f"unknown probe kind {spec['kind']}")
        except (CoreApiError, KeyError, ValueError) as e:
            log.warning("runbook %s: probe %s failed: %s", book.id, spec.get("id"), e)
            return Observation(spec["id"], spec["title"], False, None, f"{spec['title']}：查不到（{e}）"[:200],
                               error=str(e)[:200])

    return list(await asyncio.gather(*(one(spec) for spec in book.probes)))


def requirement_holds(req: dict, observations: list[Observation]) -> tuple[bool, str]:
    """Whether one of a remediation's requires holds, and why not."""
    seen = next((o for o in observations if o.probe == req["probe"]), None)
    if seen is None or not seen.ok:
        return False, f"还没有查到「{req.get('says') or req['probe']}」需要的数据"
    value = seen.value
    if "field" in req:
        value = value.get(req["field"]) if isinstance(value, dict) else None
    if req.get("nonempty") and not value:
        return False, f"不满足：{req['says']}（{seen.text}）"
    if "not_in" in req and value in req["not_in"]:
        return False, f"不满足：{req['says']}（{seen.text}）"
    if "equals" in req and value != req["equals"]:
        return False, f"不满足：{req['says']}（{seen.text}）"
    return True, f"{req['says']}（{seen.text} [{seen.ref}]）"


def params_for(remediation: dict, observations: list[Observation]) -> dict | None:
    """The parameters the runbook gives, or takes from a probe (the transaction to end is the one that was seen)."""
    if "params" in remediation:
        return dict(remediation["params"])
    source = remediation.get("params_from") or {}
    seen = next((o for o in observations if o.probe == source.get("probe") and o.ok), None)
    if seen is None or not seen.value:
        return None
    if source.get("pick") == "oldest":
        # One that is itself waiting is being held up, not holding anyone up.
        rows = sorted((t for t in seen.value if not _waiting(t)),
                      key=lambda t: (-int(t.get("blocking") or 0), -int(t["age_seconds"])))
        return {"trx_id": str(rows[0]["trx_id"]), "thread_id": int(rows[0]["thread_id"])} if rows else None
    return None


def options(book: Runbook, observations: list[Observation]) -> list[dict]:
    """Each remediation of the runbook with whether it may be proposed now, and why."""
    result = []
    for r in book.remediations:
        checks = [requirement_holds(req, observations) for req in r.get("requires", [])]
        params = params_for(r, observations)
        unmet = [why for ok, why in checks if not ok] + ([] if params is not None else ["参数还定不下来"])
        result.append({"op": r["op"], "title": r["title"], "params": params, "ready": not unmet,
                       "met": [why for ok, why in checks if ok], "unmet": unmet, "effect": r["effect"],
                       "blast_radius": r["blast_radius"], "rollback": r["rollback"]})
    return result
