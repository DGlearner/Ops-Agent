"""The case the conversation works on, kept in core-service (docs/agent-design.md §2, §13.1, §13.4).

The orchestration opens the case (or finds the one the incident is already in) when it diagnoses an incident, and
writes what it knows for sure: probe results as facts (a newer value supersedes the older one, nothing is erased),
the user's answers to a questionnaire as decisions. The model has one tool, update_case, and may only note
hypotheses (its own, marked so) and the user's statements and constraints, which must quote the user word for word:
core-service refuses the rest whatever is sent. Entries get short labels for the prompt (H1 hypothesis, F1 fact,
S1 statement, K1 constraint, D1 decision), stable because entries are only appended.
"""

import asyncio
import copy
import logging
import re
from typing import Any

from ..core_api import CoreApi, CoreApiError, CoreWriter
from .evidence import RefTable

log = logging.getLogger(__name__)

LABEL = {"hypothesis": "H", "fact": "F", "statement": "S", "constraint": "K", "decision": "D", "observation": "O",
         "note": "N"}
ACTIVE = "diagnosing"
ENTRY_KINDS = ("hypothesis", "statement", "constraint")
HYPOTHESIS_STATUS = ("candidate", "supported", "refuted", "unverifiable")


class CaseError(ValueError):
    """update_case turned down; the message goes back to the model."""


def labelled(case: dict) -> list[dict]:
    """The case's entries with their labels, oldest first."""
    counts: dict[str, int] = {}
    result = []
    for e in case.get("entries") or []:
        counts[e["kind"]] = counts.get(e["kind"], 0) + 1
        result.append({**e, "label": f"{LABEL.get(e['kind'], 'X')}{counts[e['kind']]}"})
    return result


def brief(case: dict) -> dict:
    """What the prompt shows of the case: current entries by kind, superseded ones left out."""
    shown: dict[str, list[str]] = {}
    for e in labelled(case):
        if e["status"] == "superseded":
            continue
        source = {"model": "你的推断", "rule": "规则", "analyst": "后台分析", "user": "用户", "observation": "观察",
                  "receipt": "回执", "verification": "验证"}.get(e["source_type"], e["source_type"])
        status = f"，{e['status']}" if e["kind"] == "hypothesis" else ""
        refs = "".join(f" [{x['ref']}]" for x in e.get("evidence") or [] if x.get("ref"))
        shown.setdefault(e["kind"], []).append(f"{e['label']}（{source}{status}）：{e['text']}{refs}")
    return {"case": case.get("case_id"), "status": case.get("status"), "version": case.get("state_version"),
            **{k: v for k, v in shown.items()}}


def forbidden(case: dict | None) -> dict[str, str]:
    """Operations the user's current constraints rule out, with the constraint that does."""
    out: dict[str, str] = {}
    for e in labelled(case or {}):
        if e["kind"] == "constraint" and e["status"] == "active":
            for op in (e.get("data") or {}).get("forbids") or []:
                out[op] = f"{e['label']}：{e['text']}"
    return out


class CaseDesk:
    def __init__(self, core: CoreApi, writer: CoreWriter | None):
        self._core = core
        self._writer = writer
        self._run_id: str | None = None

    def for_run(self, run_id: str) -> "CaseDesk":
        """The same desk, signing what it writes with the run (docs/agent-design.md §15.2)."""
        bound = copy.copy(self)
        bound._run_id = run_id
        return bound

    @property
    def writable(self) -> bool:
        return self._writer is not None

    async def open(self, incident_ids: list[str], title: str | None = None) -> dict | None:
        """The case these incidents are handled in, opened if need be and moved to diagnosing; None when there is
        no writer and no case yet, or core-service has no cases (an older core)."""
        if self._writer is None:
            try:
                found = await asyncio.to_thread(self._core.cases, incident_id=incident_ids[0])
            except CoreApiError:
                return None
            return next((c for c in found if c["status"] != "resolved"), None)
        outcome = await asyncio.to_thread(self._writer.open_case, incident_ids, title)
        if outcome.status == 409 and len(incident_ids) > 1:
            outcome = await asyncio.to_thread(self._writer.open_case, incident_ids[:1], title)
        if not outcome.ok or not isinstance(outcome.response, dict):
            log.warning("chat: cannot open a case for %s: %s %s", incident_ids, outcome.status, outcome.code)
            return None
        case = outcome.response
        if case["status"] == "open":
            moved = await asyncio.to_thread(self._writer.case_transition, case["case_id"], ACTIVE, case["version"])
            if moved.ok:
                case = moved.response
        return case

    async def read(self, case_id: str) -> dict:
        return await asyncio.to_thread(self._core.case, case_id)

    async def write(self, case_id: str, entry: dict) -> dict:
        """Adds an entry on the case as it is now: one retry if someone wrote in between."""
        signed = {**entry, "run_id": self._run_id} if self._run_id else entry
        for attempt in range(2):
            case = await self.read(case_id)
            outcome = await asyncio.to_thread(self._writer.add_entry, case_id, signed, case["version"])
            if outcome.ok:
                return outcome.response
            if outcome.code != "STALE_VERSION" or attempt:
                detail = outcome.response.get("detail") if isinstance(outcome.response, dict) else outcome.error
                raise CaseError(f"core-service 拒绝了：{outcome.code or outcome.status} {detail or ''}".strip())
        raise AssertionError("unreachable")

    async def record_facts(self, case: dict, observations: list, incident_id: str) -> dict:
        """Each probe's result as a fact; one whose value changed supersedes the earlier fact."""
        if self._writer is None:
            return case
        current = {(e.get("data") or {}).get("probe"): e for e in labelled(case)
                   if e["kind"] == "fact" and e["status"] == "active"}
        for o in observations:
            if not o.ok:
                continue
            before = current.get(o.probe)
            if before is not None and (before.get("data") or {}).get("value") == o.value:
                continue
            entry = {"kind": "fact", "text": o.text[:500], "source_type": "observation",
                     "evidence": [{"kind": "probe", "ref": o.ref, "incident_id": incident_id}],
                     "data": {"probe": o.probe, "value": o.value},
                     **({"supersedes": before["entry_id"]} if before else {})}
            try:
                case = await self.write(case["case_id"], entry)
            except CaseError as e:
                log.warning("chat: probe %s not recorded on case %s: %s", o.probe, case["case_id"], e)
        return case

    async def record_decision(self, case_id: str, text: str, source_ref: str, data: dict) -> None:
        if self._writer is None:
            return
        try:
            await self.write(case_id, {"kind": "decision", "text": text[:500], "source_type": "user",
                                       "source_ref": source_ref, "data": data})
        except CaseError as e:
            log.warning("chat: decision not recorded on case %s: %s", case_id, e)

    async def update(self, case_id: str, args: dict, refs: RefTable, said: list[str]) -> dict:
        """The model's update_case: a hypothesis (new, or a status change of one by label), a statement or a
        constraint quoting what the user said."""
        if self._writer is None:
            raise CaseError("这个对话服务没有开启写处置单")
        kind = str(args.get("kind") or "")
        if kind not in ENTRY_KINDS:
            raise CaseError(f"kind 只能是 {'、'.join(ENTRY_KINDS)}；事实由探针和观察写入，决定由用户做出")
        evidence = self._evidence(refs, args.get("evidence") or [])
        text = str(args.get("text") or "").strip()
        if kind == "hypothesis" and args.get("entry"):
            status = str(args.get("status") or "")
            if status not in HYPOTHESIS_STATUS:
                raise CaseError(f"status 只能是 {'、'.join(HYPOTHESIS_STATUS)}")
            case = await self.read(case_id)
            target = next((e for e in labelled(case) if e["label"] == str(args["entry"]).upper()
                           and e["kind"] == "hypothesis"), None)
            if target is None:
                raise CaseError(f"处置单里没有假设 {args['entry']}")
            if status in ("supported", "refuted") and not evidence and not target.get("evidence"):
                raise CaseError("改为 supported 或 refuted 要带上证据编号")
            outcome = await asyncio.to_thread(self._writer.entry_status, case_id, target["entry_id"], status,
                                              evidence, case["version"])
            if not outcome.ok:
                raise CaseError(f"core-service 拒绝了：{outcome.code or outcome.status}")
            return {"entry": target["label"], "status": status}
        if not text:
            raise CaseError("要写 text")
        entry: dict[str, Any] = {"kind": kind, "text": text[:500], "evidence": evidence}
        if kind == "hypothesis":
            status = str(args.get("status") or "candidate")
            if status not in HYPOTHESIS_STATUS:
                raise CaseError(f"status 只能是 {'、'.join(HYPOTHESIS_STATUS)}")
            if status in ("supported", "refuted") and not evidence:
                raise CaseError("supported 或 refuted 的假设要带上证据编号")
            entry.update({"status": status, "source_type": "model"})
        else:
            quote = str(args.get("quote") or "").strip()
            if not quote or not any(_squash(quote) in _squash(s) for s in said):
                raise CaseError("quote 必须是用户说过的原话（逐字），用户没说过的不能记成用户的说法")
            data: dict[str, Any] = {"quote": quote[:300]}
            if kind == "constraint":
                data["forbids"] = [str(op) for op in (args.get("forbids") or [])][:5]
            entry.update({"source_type": "user", "data": data})
            supersedes = args.get("supersedes")
            if supersedes:
                case = await self.read(case_id)
                old = next((e for e in labelled(case) if e["label"] == str(supersedes).upper()), None)
                if old is None or old["kind"] != kind:
                    raise CaseError(f"{supersedes} 不是处置单里的{kind}")
                entry["supersedes"] = old["entry_id"]
        case = await self.write(case_id, entry)
        added = labelled(case)[-1]
        return {"entry": added["label"], "kind": kind, "status": added["status"]}

    @staticmethod
    def _evidence(refs: RefTable, given: list) -> list[dict]:
        out = []
        for value in list(dict.fromkeys(str(v).strip().upper() for v in given))[:10]:
            item = refs.resolve(value)
            if item is None:
                raise CaseError(f"{value} 不在查到的数据里")
            out.append({"kind": item.kind, "ref": item.ref, "id": item.id, **(
                {"incident_id": item.incident_id} if item.incident_id else {})})
        return out


def tool_spec() -> dict:
    return {"type": "function", "function": {
        "name": "update_case",
        "description": ("在处置单上记下：你的假设（kind=hypothesis，带证据编号；已有假设改状态时给 entry 和 status），"
                        "用户说的情况（statement）或要求的限制（constraint，比如“别重启数据库”）。statement 和 constraint "
                        "的 quote 必须逐字引用用户的原话。事实由系统的探针写入，决定由用户做出，你不能写。"),
        "parameters": {"type": "object", "additionalProperties": False, "required": ["kind"], "properties": {
            "kind": {"enum": list(ENTRY_KINDS)},
            "text": {"type": "string", "description": "一句话"},
            "entry": {"type": "string", "description": "改已有假设的状态时，它的编号，如 H1"},
            "status": {"enum": list(HYPOTHESIS_STATUS)},
            "evidence": {"type": "array", "items": {"type": "string"}, "description": "如 [\"A1\", \"P1\"]"},
            "quote": {"type": "string", "description": "用户的原话"},
            "forbids": {"type": "array", "items": {"enum": ["start_container", "kill_transaction", "set_switch"]}},
            "supersedes": {"type": "string", "description": "用户改了说法时，被取代的那条，如 K1"}}}}}


def _squash(text: str) -> str:
    return re.sub(r"[\s，。,.!！?？、]+", "", text)
