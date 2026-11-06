"""A fake core-service with cases, remediations, verifications and the lab's probes (docs/agent-design.md §13.1,
§13.2), on top of ChatCore. It keeps core-service's rules that matter to the agent: compare-and-set on the case's
version, state_version moving only on facts, constraints, decisions and status, the digest recomputed from what would
run, the lab's preconditions checked again, one run per action_id, and verification tokens."""

import copy
import json
import re
import uuid
from datetime import datetime, timedelta, timezone

import httpx

from ops_agent.chat.actions import digest
from chat_support import ChatCore, iso

SOURCES = {"statement": {"user"}, "observation": {"observation"}, "fact": {"observation", "receipt", "verification"},
           "hypothesis": {"model", "rule", "analyst", "user"}, "constraint": {"user"}, "decision": {"user"},
           "note": {"user", "model", "system", "observation"}}
STATE = {"fact", "constraint", "decision"}
NEXT = {"open": {"diagnosing", "mitigating", "resolved", "handed_over"},
        "diagnosing": {"mitigating", "resolved", "handed_over"},
        "mitigating": {"verifying", "diagnosing", "resolved", "handed_over"},
        "verifying": {"monitoring", "diagnosing", "mitigating", "resolved", "handed_over"},
        "monitoring": {"diagnosing", "mitigating", "resolved"}, "handed_over": {"diagnosing", "resolved"},
        "resolved": set()}


class CaseCore(ChatCore):
    def __init__(self) -> None:
        super().__init__()
        self.cases: dict[str, dict] = {}
        self.containers = {"shop-mysql": "exited", "shop-redis": "running"}
        self.transactions: list[dict] = []
        self.switches = {"payment.fast-fail": False, "inventory.fast-fail": False}
        self.lab_changes: list[str] = []
        self.remediations: list[dict] = []
        self.verifications: list[dict] = []
        self.start_does_nothing = False

    # Helpers for tests.

    def case_of(self, incident_id: str) -> dict | None:
        return next((c for c in self.cases.values() if incident_id in c["incident_ids"]), None)

    def handle(self, request: httpx.Request) -> httpx.Response:
        path = request.url.path
        if path.startswith(("/api/v1/cases", "/api/v1/remediations", "/api/v1/verifications", "/api/v1/lab")):
            self.requests.append(request)
            body = json.loads(request.content) if request.content else None
            try:
                status, payload = self._route(request.method, path, request.url.params, body)
            except Problem as p:
                status, payload = p.status, {"code": p.code, "detail": p.detail}
            if status == 204:
                return httpx.Response(204)
            return httpx.Response(status, json=copy.deepcopy(payload))
        return super().handle(request)

    def _route(self, method: str, path: str, params, body: dict | None) -> tuple[int, dict]:
        if path == "/api/v1/cases" and method == "POST":
            return self._open(body)
        if path == "/api/v1/cases":
            items = [c for c in self.cases.values()
                     if params.get("incident_id") in (None, *c["incident_ids"])]
            return 200, {"items": items}
        m = re.fullmatch(r"/api/v1/cases/([^/]+)(/.*)?", path)
        if m:
            case = self.cases.get(m.group(1))
            if case is None:
                raise Problem(404, "CASE_NOT_FOUND", "no case")
            rest = m.group(2) or ""
            if method == "GET" and not rest:
                return 200, case
            if rest == "/entries":
                return 201, self._entry(case, body)
            sm = re.fullmatch(r"/entries/([^/]+)/status", rest)
            if sm:
                return 200, self._entry_status(case, sm.group(1), body)
            if rest == "/transitions":
                self._cas(case, body.get("expected_version"))
                if body["to_status"] not in NEXT[case["status"]]:
                    raise Problem(409, "ILLEGAL_TRANSITION", "no")
                if body["to_status"] == "resolved" and body.get("actor") != "user":
                    raise Problem(409, "SIGN_OFF_REQUIRED", "a person signs off")
                self._move(case, body["to_status"])
                return 200, case
        if path == "/api/v1/remediations" and method == "POST":
            return 200, self._remediate(body)
        if path == "/api/v1/remediations":
            return 200, {"items": [r for r in self.remediations if r["case_id"] == params.get("case_id")]}
        if path == "/api/v1/verifications":
            return 200, {"items": [v for v in self.verifications if v["case_id"] == params.get("case_id")]}
        if path == "/api/v1/verifications/claim":
            due = next((v for v in self.verifications if v["status"] == "pending"), None)
            if due is None:
                return 204, {}
            due.update(status="leased", token=due["token"] + 1, lease_owner=body["worker"])
            return 200, due
        vm = re.fullmatch(r"/api/v1/verifications/([^/]+)/result", path)
        if vm:
            v = next(v for v in self.verifications if v["verification_id"] == vm.group(1))
            if v["status"] != "leased" or v["token"] != body["token"]:
                raise Problem(409, "STALE_TOKEN", "not yours")
            v.update(status="done", outcome=body["outcome"], result=body["result"])
            case = self.cases[v["case_id"]]
            target = {"recovered": "monitoring", "not_recovered": "diagnosing"}.get(body["outcome"])
            if target and target in NEXT[case["status"]]:
                self._move(case, target)
            return 200, v
        if path.startswith("/api/v1/lab/containers/"):
            name = path.rsplit("/", 1)[1]
            if name not in self.containers:
                raise Problem(404, "CONTAINER_NOT_FOUND", name)
            return 200, {"name": name, "state": self.containers[name]}
        if path == "/api/v1/lab/transactions":
            return 200, {"items": [t for t in self.transactions
                                   if t["age_seconds"] >= int(params.get("min_age_s", 0))]}
        if path == "/api/v1/lab/switches":
            return 200, {"items": [{"name": k, "on": v, "until": None} for k, v in sorted(self.switches.items())]}
        raise Problem(404, "NOT_FOUND", path)

    def _open(self, body: dict) -> tuple[int, dict]:
        ids = body["incident_ids"]
        open_cases = {c["case_id"] for c in self.cases.values() if c["status"] != "resolved"
                      and set(ids) & set(c["incident_ids"])}
        if len(open_cases) > 1:
            raise Problem(409, "CASE_CONFLICT", "two cases")
        if open_cases:
            return 200, self.cases[open_cases.pop()]
        case_id = str(uuid.uuid4())
        self.cases[case_id] = {"case_id": case_id, "title": body.get("title") or "case", "status": "open",
                               "version": 1, "state_version": 1, "incident_ids": list(ids), "entries": [],
                               "events": []}
        return 201, self.cases[case_id]

    def _cas(self, case: dict, expected: int | None) -> None:
        if expected != case["version"]:
            raise Problem(409, "STALE_VERSION", f"at {case['version']}")

    def _move(self, case: dict, to: str) -> None:
        case.update(status=to, version=case["version"] + 1, state_version=case["state_version"] + 1)

    def _entry(self, case: dict, body: dict) -> dict:
        self._cas(case, body.get("expected_version"))
        kind = body["kind"]
        if body.get("source_type") not in SOURCES.get(kind, set()):
            raise Problem(400, "VALIDATION_FAILED", f"a {kind} cannot come from {body.get('source_type')}")
        if kind in ("fact", "observation") and not (body.get("evidence") or body.get("data")):
            raise Problem(400, "VALIDATION_FAILED", "needs evidence")
        if kind in ("statement", "constraint") and not (body.get("data") or {}).get("quote"):
            raise Problem(400, "VALIDATION_FAILED", "needs the quote")
        if kind == "decision" and not body.get("source_ref"):
            raise Problem(400, "VALIDATION_FAILED", "needs source_ref")
        entry = {"entry_id": str(uuid.uuid4()), "kind": kind, "status": body.get("status") or (
            "candidate" if kind == "hypothesis" else "active"), "text": body["text"], "source_type": body["source_type"],
                 "source_ref": body.get("source_ref"), "evidence": body.get("evidence") or [],
                 "data": body.get("data") or {}, "supersedes": body.get("supersedes"), "run_id": body.get("run_id")}
        if entry["supersedes"]:
            old = next(e for e in case["entries"] if e["entry_id"] == entry["supersedes"])
            old.update(status="superseded", superseded_by=entry["entry_id"])
        case["entries"].append(entry)
        case["version"] += 1
        if kind in STATE:
            case["state_version"] += 1
        return case

    def _entry_status(self, case: dict, entry_id: str, body: dict) -> dict:
        self._cas(case, body.get("expected_version"))
        entry = next(e for e in case["entries"] if e["entry_id"] == entry_id)
        entry["status"] = body["status"]
        entry["evidence"] = entry["evidence"] + (body.get("evidence") or [])
        case["version"] += 1
        return case

    def _remediate(self, body: dict) -> dict:
        existing = next((r for r in self.remediations if r["action_id"] == body["action_id"]), None)
        payload = {"case_id": body["case_id"], "op": body["op"], "params": body["params"],
                   **({"incident_id": body["incident_id"]} if body.get("incident_id") else {})}
        if existing:
            return existing
        if digest(payload) != body["payload_digest"]:
            raise Problem(409, "PAYLOAD_MISMATCH", "not what would run")
        case = self.cases[body["case_id"]]
        if case["state_version"] != body["expected_case_version"]:
            raise Problem(409, "STALE_VERSION", "the case changed")
        op, p = body["op"], body["params"]
        r = {"remediation_id": str(uuid.uuid4()), "action_id": body["action_id"], "case_id": case["case_id"],
             "op": op, "params": p, "payload_digest": body["payload_digest"], "run_id": body.get("run_id"),
             "session_id": body.get("session_id"), "snapshot_digest": body.get("snapshot_digest")}
        self.remediations.append(r)
        if "mitigating" in NEXT[case["status"]]:
            self._move(case, "mitigating")
        if op == "start_container":
            if self.containers.get(p["name"]) == "running":
                r.update(status="blocked", receipt={"summary": f"没有执行：{p['name']} 已经在运行"})
                self._move(case, "diagnosing")
                return r
            self.lab_changes.append(f"start {p['name']}")
            if not self.start_does_nothing:
                self.containers[p["name"]] = "running"
            ok = self.containers[p["name"]] == "running"
            r.update(status="executed" if ok else "failed",
                     receipt={"summary": f"已启动 {p['name']}" if ok else "读回不是 running",
                              "observed": {"state": self.containers[p["name"]]}})
        elif op == "kill_transaction":
            if not any(t["trx_id"] == p["trx_id"] for t in self.transactions):
                r.update(status="blocked", receipt={"summary": f"没有执行：事务 {p['trx_id']} 已经结束"})
                self._move(case, "diagnosing")
                return r
            self.lab_changes.append(f"kill {p['thread_id']}")
            self.transactions = [t for t in self.transactions if t["trx_id"] != p["trx_id"]]
            r.update(status="executed", receipt={"summary": f"已结束事务 {p['trx_id']}"})
        else:
            self.lab_changes.append(f"switch {p['name']} {p['on']}")
            self.switches[p["name"]] = p["on"]
            r.update(status="executed", receipt={"summary": f"已设置 {p['name']}"})
        if r["status"] == "executed":
            now = datetime.now(timezone.utc)
            for v in self.verifications:
                if v["case_id"] == case["case_id"] and v["status"] in ("pending", "leased"):
                    v["status"] = "stale"
            self.verifications.append({
                "verification_id": str(uuid.uuid4()), "case_id": case["case_id"],
                "remediation_id": r["remediation_id"], "run_id": r["run_id"], "status": "pending", "token": 0,
                "window_from": iso(now - timedelta(minutes=2)), "not_before": iso(now),
                "criteria": {"goal": "recover", "checks": [
                    {"id": "no_new_alerts", "kind": "no_alerts", "services": ["order-service"], "title": "无新告警"},
                    {"id": "orders_paid", "kind": "events_at_least", "service": "order-service",
                     "event_type": "order_paid", "min": 1, "title": "有成功订单"}]}})
            self._move(case, "verifying")
        else:
            self._move(case, "diagnosing")
        return r


class Problem(Exception):
    def __init__(self, status: int, code: str, detail: str):
        super().__init__(detail)
        self.status, self.code, self.detail = status, code, detail
