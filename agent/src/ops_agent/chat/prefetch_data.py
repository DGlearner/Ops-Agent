"""Versioned, read-only captures for prefetch evaluation. Replay is explicitly NOT live end-to-end."""
from __future__ import annotations

import copy
import hashlib
import json
from collections import Counter
from datetime import datetime, timedelta, timezone
from pathlib import Path

import httpx

from ..core_api import CoreApi, parse_time

RANK = {"info": 0, "low": 1, "medium": 2, "high": 3, "critical": 4}


def digest(value) -> str:
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, sort_keys=True).encode()).hexdigest()


def write(path: Path, value) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")


def capture(url: str, out: Path, hours: int = 22) -> dict:
    """Export every page, not merely the first 200 log rows. Bounded to one API-supported day."""
    if not 1 <= hours <= 23:
        raise ValueError("capture hours must be 1..23")
    end = datetime.now(timezone.utc)
    start = end - timedelta(hours=hours)
    with httpx.Client(base_url=url, timeout=30, trust_env=False) as client:
        def get(path, **params):
            r = client.get(path, params=params); r.raise_for_status(); return r.json()
        incidents = get("/api/v1/incidents", **{"from": start.isoformat(), "to": end.isoformat(), "limit": 200})["items"]
        data = {"version": 1, "captured_at": end.isoformat(), "from": start.isoformat(), "to": end.isoformat(),
                "provenance": "live core-service API; application ECS logs only; no native database/broker logs",
                "topology": get("/api/v1/topology"), "incidents": incidents,
                "alerts": {}, "analyses": {}, "timelines": {}, "logs": []}
        for incident in incidents:
            key = incident["incident_id"]
            for field, suffix in (("alerts", "alerts"), ("analyses", "analyses"), ("timelines", "timeline")):
                data[field][key] = get(f"/api/v1/incidents/{key}/{suffix}")
        cursor = None
        while True:
            params = {"from": start.isoformat(), "to": end.isoformat(), "limit": 200}
            if cursor:
                params["cursor"] = cursor
            page = get("/api/v1/logs", **params)
            data["logs"].extend(page["items"])
            cursor = page.get("next_cursor")
            if not cursor:
                break
            if len(data["logs"]) > 100_000:
                raise ValueError("capture exceeded 100000 rows; choose a narrower window; no silent truncation")
    data["counts"] = {"incidents": len(incidents), "alerts": sum(map(len, data["alerts"].values())),
                      "logs": len(data["logs"]), "logs_by_service": dict(Counter(r["service"] for r in data["logs"]))}
    data["sha256"] = digest(data)
    write(out, data)
    return data


class SnapshotCore:
    """CoreApi-compatible read adapter over a full bounded capture. All requests are GET-only."""
    def __init__(self, data: dict):
        self.data = copy.deepcopy(data)

    def api(self) -> CoreApi:
        return CoreApi("http://snapshot", client=httpx.Client(base_url="http://snapshot",
                       transport=httpx.MockTransport(self.handle)))

    def handle(self, request: httpx.Request) -> httpx.Response:
        if request.method != "GET":
            return httpx.Response(405, json={"detail": "evaluation is read-only"})
        d, p, q = self.data, request.url.path, request.url.params
        limit = int(q.get("limit", 20))
        def within(value):
            t = parse_time(value)
            return (not q.get("from") or t >= parse_time(q["from"])) and (
                    not q.get("to") or t < parse_time(q["to"]))
        if p == "/api/v1/topology":
            return httpx.Response(200, json=d["topology"])
        if p == "/api/v1/incidents":
            rows = [i for i in d["incidents"] if within(i["opened_at"])
                    and (not q.get("status") or i["status"] == q["status"])
                    and (not q.get("service") or q["service"] in i.get("services", []))]
            rows = [i for i in rows if not q.get("cursor") or i["incident_id"] < q["cursor"]]
            return httpx.Response(200, json={"items": sorted(rows, key=lambda i: i["incident_id"], reverse=True)[:limit]})
        if p == "/api/v1/alerts":
            rows = [a for items in d["alerts"].values() for a in items if within(a["created_at"])
                    and (not q.get("service") or a["service"] == q["service"])
                    and (not q.get("rule_id") or a["rule_id"] == q["rule_id"])]
            return httpx.Response(200, json={"items": sorted(rows, key=lambda a: a["created_at"], reverse=True)[:limit]})
        if p in ("/api/v1/logs", "/api/v1/logs/counts"):
            rows = [r for r in d["logs"] if within(r["timestamp"])
                    and all(not q.get(field) or r.get(field) == q[field] for field in ("service", "event_type", "trace_id"))
                    and RANK[r["severity"]] >= RANK[q.get("min_severity", "info")]
                    and (not q.get("q") or q["q"].lower() in (r.get("message") or "").lower())]
            rows.sort(key=lambda r: (parse_time(r["timestamp"]), r["event_id"]), reverse=True)
            if p.endswith("/counts"):
                by = q.get("by", "event_type")
                groups = {}
                for r in rows:
                    key = r.get(by) or ""
                    if key not in groups:
                        groups[key] = {"key": key, "count": 0, "severity": r["severity"], "example": r["message"],
                                       "first_at": r["timestamp"], "last_at": r["timestamp"]}
                    g = groups[key]; g["count"] += 1
                    if parse_time(r["timestamp"]) < parse_time(g["first_at"]): g["first_at"] = r["timestamp"]
                    if RANK[r["severity"]] > RANK[g["severity"]]: g["severity"] = r["severity"]
                return httpx.Response(200, json=sorted(groups.values(), key=lambda g: (-g["count"], g["key"]))[:limit])
            return httpx.Response(200, json={"items": rows[:limit]})
        for i in d["incidents"]:
            key = i["incident_id"]; base = f"/api/v1/incidents/{key}"
            values = {base: i, base+"/alerts": d["alerts"].get(key, []),
                      base+"/analyses": d["analyses"].get(key, []), base+"/timeline": d["timelines"].get(key, [])}
            if p in values: return httpx.Response(200, json=values[p])
        return httpx.Response(404, json={"detail": "not present in the bounded snapshot", "code": "NOT_FOUND"})


def scenario(data: dict, mutation: str | None, target: str | None) -> dict:
    """Explicit controlled counterfactuals; never reported as real injected faults."""
    result = copy.deepcopy(data)
    if not mutation: return result
    if mutation == "missing":
        result["logs"] = []
        result["analyses"][target] = []
        for alert in result["alerts"].get(target, []):
            alert["samples"] = []; alert["sample_event_ids"] = []
    elif mutation in ("pending", "stale", "failed"):
        i = next(i for i in result["incidents"] if i["incident_id"] == target)
        at = parse_time(i["last_alert_at"]) + timedelta(seconds=1 if mutation != "stale" else -600)
        a = {"analysis_id": "eval-"+mutation, "incident_id": target,
             "status": "completed" if mutation == "stale" else mutation, "requested_at": at.isoformat(),
             "model": "controlled-fixture", "summary": "历史报告称 shop-redis 故障，尚未覆盖本次数据库错误",
             "uncertain": False, "hypotheses": [{"component": "shop-redis", "cause": "old report",
                                                "confidence": "high", "evidence": []}]}
        result["analyses"][target] = [a]
    else: raise ValueError(mutation)
    result["mutation"] = mutation
    return result
