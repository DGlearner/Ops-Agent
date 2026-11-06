"""The verify worker (docs/agent-design.md §6, §13.2): claims a verification that is due, judges it, and hands the
result back with the token it was claimed with. Every check is decided from alerts and log counts in the window
after the remediation; no model. A check with no data to go on is insufficient evidence, not a pass: no orders at
all in the window says nothing about whether orders succeed.

core-service keeps the lease: a worker that stops or stalls loses the verification to the next claim, and its late
result is refused (409 STALE_TOKEN).
"""

import logging
import socket
import threading
import time
from datetime import datetime, timezone

from .core_api import CoreApi, CoreApiError, CoreWriter, parse_time

log = logging.getLogger(__name__)

MET, NOT_MET, INSUFFICIENT = "met", "not_met", "insufficient"


class Verifier:
    def __init__(self, core: CoreApi, writer: CoreWriter, worker: str | None = None):
        self._core = core
        self._writer = writer
        self.worker = worker or f"verify@{socket.gethostname()}"

    def run(self, stop: threading.Event, poll_s: float = 5) -> None:
        log.info("verify: %s polling core-service every %.0f s", self.worker, poll_s)
        while not stop.is_set():
            try:
                if self.once():
                    continue
            except Exception:  # noqa: BLE001 - keep polling; the lease brings the verification back
                log.exception("verify: a round failed")
            stop.wait(poll_s)

    def once(self) -> dict | None:
        """Claims and judges one verification; None when none is due."""
        claimed = self._writer.claim_verification(self.worker)
        if claimed.status == 204 or not claimed.ok or not isinstance(claimed.response, dict):
            if claimed.status not in (204, 200):
                log.warning("verify: claim failed: %s %s", claimed.status, claimed.error or claimed.code)
            return None
        v = claimed.response
        outcome, result = self.judge(v)
        sent = self._writer.verification_result(v["verification_id"], v["token"], outcome, result)
        if not sent.ok:
            log.warning("verify: result for %s not taken: %s %s", v["verification_id"], sent.status, sent.code)
        else:
            log.info("verify: case %s (run %s): %s", v["case_id"], v.get("run_id"), outcome)
        return {"verification": v, "outcome": outcome, "result": result, "accepted": sent.ok}

    def judge(self, v: dict) -> tuple[str, dict]:
        criteria = v["criteria"]
        start = parse_time(v["window_from"])
        end = min(parse_time(v["not_before"]), datetime.now(timezone.utc))
        if end <= start:
            end = datetime.now(timezone.utc)
        checks = [self._check(c, start, end) for c in criteria.get("checks") or []]
        verdicts = {c["verdict"] for c in checks}
        if NOT_MET in verdicts:
            outcome = "not_recovered"
        elif INSUFFICIENT in verdicts or not checks:
            outcome = "inconclusive"
        else:
            outcome = "recovered"
        return outcome, {"run_id": v.get("run_id"), "goal": criteria.get("goal", "recover"), "window": {"from": v["window_from"],
                                                                            "to": end.isoformat()},
                         "checks": checks, "worker": self.worker, "judged_at": datetime.now(timezone.utc).isoformat()}

    def _check(self, c: dict, start: datetime, end: datetime) -> dict:
        base = {"id": c["id"], "title": c.get("title", c["id"])}
        try:
            if c["kind"] == "no_alerts":
                found = []
                for service in c.get("services") or []:
                    found += self._core.alerts(start=start, end=end, service=service, limit=20)
                # An alert is about the window it counted in: one raised after the remediation about the minute
                # before it (the baseline detector closes a minute when it is over) is the fault, not its aftermath.
                found = [a for a in found if parse_time(a.get("window_start") or a["created_at"]) >= start]
                ids = [a["alert_id"] for a in found][:10]
                return {**base, "verdict": MET if not found else NOT_MET, "evidence": ids,
                        "reason": "窗口内没有新的告警" if not found else f"窗口内有 {len(found)} 条新告警"}
            if c["kind"] == "events_at_least":
                counts = self._core.log_counts(start=start, end=end, service=c["service"], by="event_type", limit=50)
                total = sum(int(r.get("count", 0)) for r in counts)
                got = next((int(r.get("count", 0)) for r in counts if r.get("value") == c["event_type"]
                            or r.get("key") == c["event_type"]), 0)
                if got >= int(c["min"]):
                    return {**base, "verdict": MET, "reason": f"{c['event_type']} {got} 次", "evidence": []}
                if total == 0:
                    return {**base, "verdict": INSUFFICIENT, "evidence": [],
                            "reason": f"窗口内 {c['service']} 没有任何日志，判断不了"}
                return {**base, "verdict": NOT_MET, "evidence": [],
                        "reason": f"{c['event_type']} {got} 次（{c['service']} 共 {total} 行日志）"}
            if c["kind"] == "logs_at_most":
                rows = self._core.logs(start=start, end=end, service=c["service"], event_type=c.get("event_type"),
                                       text=c.get("text"), limit=int(c["max"]) + 5)
                ids = [r.get("event_id") for r in rows][:10]
                ok = len(rows) <= int(c["max"])
                return {**base, "verdict": MET if ok else NOT_MET, "evidence": ids,
                        "reason": f"相关日志 {len(rows)} 行" + ("" if ok else f"，超过 {c['max']}")}
            return {**base, "verdict": INSUFFICIENT, "reason": f"不认识的检查 {c['kind']}", "evidence": []}
        except CoreApiError as e:
            return {**base, "verdict": INSUFFICIENT, "reason": f"查不到数据：{e}"[:200], "evidence": []}


def wait_for(core: CoreApi, case_id: str, timeout_s: float, poll_s: float = 3) -> dict | None:
    """The case's latest verification once it is done (for the outcome evaluation)."""
    deadline = time.monotonic() + timeout_s
    while time.monotonic() < deadline:
        found = [v for v in core.verifications(case_id) if v["status"] == "done"]
        if found:
            return found[-1]
        time.sleep(poll_s)
    return None
