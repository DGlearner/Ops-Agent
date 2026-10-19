"""core-service's read-only API: the only way the agent sees the system it analyses."""

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

    def analyses(self, incident_id: UUID | str) -> list[dict]:
        """The incident's analyses by the agent, newest first."""
        return self._get(f"/api/v1/incidents/{incident_id}/analyses")

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


def parse_time(value: str) -> datetime:
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


def _iso(t: datetime) -> str:
    return t.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")
