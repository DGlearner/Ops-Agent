"""core-service's API. CoreApi is read-only: the only way the agents see the system they analyse, and all the
model's tools ever get. CoreWriter is the chat's few writes, used only by chat.actions to run an action the user
confirmed; nothing the model calls holds one."""

from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any
from uuid import UUID

import httpx


class CoreApiError(RuntimeError):
    pass


class CoreApi:
    def __init__(self, base_url: str, timeout: float = 10.0, client: httpx.Client | None = None):
        self._http = client or httpx.Client(base_url=base_url, timeout=timeout)

    def incident(self, incident_id: UUID | str) -> dict:
        return self._get(f"/api/v1/incidents/{incident_id}")

    def incident_alerts(self, incident_id: UUID | str) -> list[dict]:
        return self._get(f"/api/v1/incidents/{incident_id}/alerts")

    def incident_timeline(self, incident_id: UUID | str) -> list[dict]:
        return self._get(f"/api/v1/incidents/{incident_id}/timeline")

    def topology(self) -> dict:
        return self._get("/api/v1/topology")

    def alerts(self, *, start: datetime, end: datetime, service: str | None = None, rule_id: str | None = None,
               limit: int = 50) -> list[dict]:
        """Alerts created in [start, end), newest first."""
        params = {"from": _iso(start), "to": _iso(end), "limit": limit, "service": service, "rule_id": rule_id}
        return self._get("/api/v1/alerts", params)["items"]

    def incidents(self, *, start: datetime, end: datetime, service: str | None = None, status: str | None = None,
                  limit: int = 20) -> list[dict]:
        """Incidents opened in [start, end), newest first."""
        params = {"from": _iso(start), "to": _iso(end), "limit": limit, "service": service, "status": status}
        return self._get("/api/v1/incidents", params)["items"]

    def logs(self, *, start: datetime, end: datetime, service: str | None = None, min_severity: str | None = None,
             event_type: str | None = None, trace_id: str | None = None, text: str | None = None,
             limit: int = 20) -> list[dict]:
        """Stored log lines whose own time is in [start, end), newest first (docs/project-design.md §8.7)."""
        params = {"from": _iso(start), "to": _iso(end), "service": service, "min_severity": min_severity,
                  "event_type": event_type, "trace_id": trace_id, "q": text, "limit": limit}
        return self._get("/api/v1/logs", params)["items"]

    def log_counts(self, *, start: datetime, end: datetime, service: str | None = None, by: str = "event_type",
                   min_severity: str | None = None, text: str | None = None, limit: int = 20) -> list[dict]:
        """How many lines share each event type (or severity, service, host) in [start, end), the most first."""
        params = {"from": _iso(start), "to": _iso(end), "service": service, "by": by, "min_severity": min_severity,
                  "q": text, "limit": limit}
        return self._get("/api/v1/logs/counts", params)

    def analyses(self, incident_id: UUID | str) -> list[dict]:
        """The incident's analyses by the agent, newest first."""
        return self._get(f"/api/v1/incidents/{incident_id}/analyses")

    # Cases and what was done on them (docs/agent-design.md §13.1, §13.2).

    def case(self, case_id: str) -> dict:
        return self._get(f"/api/v1/cases/{case_id}")

    def cases(self, *, incident_id: str | None = None, status: str | None = None, limit: int = 20) -> list[dict]:
        return self._get("/api/v1/cases", {"incident_id": incident_id, "status": status, "limit": limit})["items"]

    def remediations(self, case_id: str) -> list[dict]:
        return self._get("/api/v1/remediations", {"case_id": case_id})["items"]

    def verifications(self, case_id: str) -> list[dict]:
        return self._get("/api/v1/verifications", {"case_id": case_id})["items"]

    # Read-only probes of the shop lab, for the runbooks.

    def lab_container(self, name: str) -> dict:
        return self._get(f"/api/v1/lab/containers/{name}")

    def lab_transactions(self, min_age_s: int = 5) -> list[dict]:
        return self._get("/api/v1/lab/transactions", {"min_age_s": min_age_s})["items"]

    def lab_switches(self) -> list[dict]:
        return self._get("/api/v1/lab/switches")["items"]

    def close(self) -> None:
        self._http.close()

    def _get(self, path: str, params: dict | None = None) -> Any:
        query = {k: v for k, v in (params or {}).items() if v is not None}
        try:
            response = self._http.get(path, params=query)
        except httpx.HTTPError as e:
            raise CoreApiError(f"GET {path}: {e}") from e
        if response.status_code != 200:
            detail = response.text[:300]
            try:
                detail = response.json().get("detail", detail)
            except ValueError:
                pass
            raise CoreApiError(f"GET {path}: {response.status_code} {detail}")
        return response.json()


@dataclass(frozen=True)
class WriteOutcome:
    """What core-service answered to a write, kept as it was for the receipt. status 0: no answer (the request may
    or may not have reached it)."""
    method: str
    path: str
    body: dict | None
    status: int
    response: Any = None
    error: str | None = None

    @property
    def ok(self) -> bool:
        return 200 <= self.status < 300

    @property
    def code(self) -> str | None:
        return self.response.get("code") if isinstance(self.response, dict) else None


class CoreWriter:
    def __init__(self, base_url: str, timeout: float = 10.0, client: httpx.Client | None = None):
        self._http = client or httpx.Client(base_url=base_url, timeout=timeout)

    def transition(self, incident_id: str, to_status: str, *, note: str | None, expected_version: int,
                   action_id: str) -> WriteOutcome:
        """Moves the incident on only if it is still at expected_version (409 STALE_VERSION otherwise)."""
        body = {"to_status": to_status, "expected_version": expected_version, "action_id": action_id,
                **({"note": note} if note else {})}
        return self._post(f"/api/v1/incidents/{incident_id}/transitions", body)

    def request_analysis(self, incident_id: str) -> WriteOutcome:
        """202 with the pending analysis; one already pending is returned instead of a second."""
        return self._post(f"/api/v1/incidents/{incident_id}/analyses", None)

    # Cases: the agent's working state, kept in core-service (docs/agent-design.md §2, §13.1).

    def open_case(self, incident_ids: list[str], title: str | None = None) -> WriteOutcome:
        """201 with a new case, 200 with the one these incidents are already in, 409 CASE_CONFLICT."""
        return self._post("/api/v1/cases", {"incident_ids": incident_ids, "actor": "agent",
                                             **({"title": title} if title else {})})

    def add_entry(self, case_id: str, entry: dict, expected_version: int) -> WriteOutcome:
        return self._post(f"/api/v1/cases/{case_id}/entries", {**entry, "expected_version": expected_version})

    def entry_status(self, case_id: str, entry_id: str, status: str, evidence: list[dict],
                     expected_version: int) -> WriteOutcome:
        return self._post(f"/api/v1/cases/{case_id}/entries/{entry_id}/status",
                          {"status": status, "evidence": evidence, "expected_version": expected_version})

    def case_transition(self, case_id: str, to_status: str, expected_version: int, *, actor: str = "agent",
                        note: str | None = None) -> WriteOutcome:
        return self._post(f"/api/v1/cases/{case_id}/transitions",
                          {"to_status": to_status, "actor": actor, "expected_version": expected_version,
                           **({"note": note} if note else {})})

    def remediate(self, *, action_id: str, case_id: str, incident_id: str | None, op: str, params: dict,
                  payload_digest: str, expected_case_version: int, origin: dict | None = None) -> WriteOutcome:
        """Runs a confirmed card once (409 PAYLOAD_MISMATCH, STALE_VERSION); the same action_id again returns the
        same remediation. origin: the run_id, session_id and snapshot_digest it came from, kept for tracing."""
        return self._post("/api/v1/remediations", {
            "action_id": action_id, "case_id": case_id, "op": op, "params": params, "payload_digest": payload_digest,
            "expected_case_version": expected_case_version, **({"incident_id": incident_id} if incident_id else {}),
            **{k: v for k, v in (origin or {}).items() if v is not None}})

    # Verifications, for the verify worker.

    def claim_verification(self, worker: str) -> WriteOutcome:
        """200 with a verification and its token, or 204 when none is due."""
        return self._post("/api/v1/verifications/claim", {"worker": worker})

    def verification_result(self, verification_id: str, token: int, outcome: str, result: dict) -> WriteOutcome:
        return self._post(f"/api/v1/verifications/{verification_id}/result",
                          {"token": token, "outcome": outcome, "result": result})

    def close(self) -> None:
        self._http.close()

    def _post(self, path: str, body: dict | None) -> WriteOutcome:
        try:
            response = self._http.post(path, json=body)
        except httpx.HTTPError as e:
            return WriteOutcome("POST", path, body, 0, error=f"{type(e).__name__}: {e}"[:300])
        try:
            payload = response.json() if response.content else None
        except ValueError:
            payload = response.text[:300]
        return WriteOutcome("POST", path, body, response.status_code, payload)


def parse_time(value: str) -> datetime:
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


def _iso(t: datetime) -> str:
    return t.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")
