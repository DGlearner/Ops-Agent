"""Finite paired HTTP/SSE evaluation, with labeled evidence groups and retained failures.

python -m ops_agent.chat.prefetch_eval --help
No operational writer, fault injection, grading model, or persistent scheduler is used here.
"""
from __future__ import annotations

import argparse
import asyncio
import copy
import hashlib
import json
import math
import os
import random
import re
import socket
import time
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path

import httpx
import uvicorn

from ..config import Settings
from ..core_api import CoreApi
from . import agent as agent_module
from .agent import ChatAgent
from .llm import OpenAIStreamingChat
from .memory import ChatStore
from .prefetch_data import SnapshotCore, capture, digest, scenario, write
from .server import create_app

DEFAULT = Path(__file__).resolve().parents[3] / "eval/prefetch"


def load_cases(path: Path) -> list[dict]:
    rows = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
    ids = [r["id"] for r in rows]
    if len(ids) != len(set(ids)): raise ValueError("duplicate case ids")
    for row in rows:
        for field in ("id", "group", "question", "cohort", "category", "now", "targets", "window",
                      "services", "routes", "behavior", "required", "rubric"):
            if field not in row: raise ValueError(f"{row['id']}: missing {field}")
        if not row["required"]: raise ValueError("empty evidence oracle")
    return rows


class ObservedCore(CoreApi):
    def __init__(self, base: CoreApi):
        self.base = base
        self.calls: list[dict] = []
        self.phase = "prefetch"

    def _get(self, path, params=None):
        start = time.perf_counter()
        row = {"path": path, "params": params or {}, "phase": self.phase}
        try:
            value = self.base._get(path, params)
            row.update(ok=True, bytes=len(json.dumps(value, ensure_ascii=False).encode()),
                       rows=len(value) if isinstance(value, list) else len(value.get("items", [])) if isinstance(value, dict) else 0)
            return value
        except Exception as e:
            row.update(ok=False, error=type(e).__name__)
            raise
        finally:
            row["duration_ms"] = round((time.perf_counter()-start)*1000, 3)
            self.calls.append(row)


class ObservedModel:
    def __init__(self, base, core):
        self.base, self.core = base, core
        self.name = base.name
        self.calls: list[dict] = []

    async def stream(self, messages, tools=None, **kw):
        self.core.phase = "supplement"
        row = {"kind": "stream", "messages": copy.deepcopy(messages), "tools": copy.deepcopy(tools),
               "thinking": kw.get("thinking")}
        self.calls.append(row)
        turn = await self.base.stream(messages, tools, **kw)
        row["tool_calls"] = [{"name": c.name, "arguments": c.arguments} for c in turn.tool_calls]
        row["usage"] = turn.usage
        return turn

    async def quick(self, messages, **kw):
        self.calls.append({"kind": "quick", "messages": copy.deepcopy(messages)})
        return await self.base.quick(messages, **kw)


def first_pack(calls):
    for call in calls:
        if call["kind"] != "stream": continue
        for message in reversed(call["messages"]):
            content = message.get("content") or ""
            marker = "已查到的数据：\n```json\n"
            if marker in content:
                return json.loads(content.split(marker, 1)[1].rsplit("\n```", 1)[0])
    return None


def values_with_refs(value, refs) -> set[str]:
    found = set()
    if isinstance(value, dict):
        for key, item in value.items():
            if key == "ref" and item in refs: found.add(refs[item]["id"])
            found |= values_with_refs(item, refs)
    elif isinstance(value, list):
        for item in value: found |= values_with_refs(item, refs)
    return found


def evidence_checks(case, pack, refs, events) -> list[dict]:
    """Exact source-id groups plus explicit absence/status predicates, not 'nonempty returned'."""
    pack = pack or {}
    present = values_with_refs(pack, refs)
    paused = any(n == "questionnaire" for n, _ in events)
    checks = []
    for group in case["required"]:
        kind = group.get("kind", "source")
        if kind == "source":
            candidates = present
            if group.get("incident_id"):
                details = [i for i in pack.get("incidents", [])
                           if refs.get(i.get("ref"), {}).get("id") == group["incident_id"]]
                candidates = values_with_refs(details, refs)
            hit = sorted(candidates.intersection(group["ids"]))
            ok = len(hit) >= group.get("min", 1)
        elif kind == "absence":
            # A scope AND empty query results are required. Empty pack is not evidence of absence.
            ok = bool(pack.get("scope")) and "recent_alerts" in pack and not pack["recent_alerts"] and not pack.get("incidents")
            hit = []
        elif kind == "service_context":
            contexts = [i.get("service_logs", {}).get(group["service"]) for i in pack.get("incidents", [])
                        if refs.get(i.get("ref"), {}).get("id") in case["targets"]]
            expected = group["event_counts"]
            ok = any(c is not None and "warnings_and_errors" in c and
                     {e["event_type"]: e["count"] for e in c.get("events", [])} == expected
                     for c in contexts)
            hit = []
        elif kind == "task":
            tasks = [i.get("background_task", {}) for i in pack.get("incidents", [])]
            ok = any(t.get("status") == group["status"] and
                     ("stale" not in group or t.get("stale") == group["stale"]) for t in tasks)
            if group.get("no_applicable_report"):
                ok = ok and all(not i.get("background_analysis") for i in pack.get("incidents", []))
            hit = []
        elif kind == "clarify":
            # The orchestration can correctly clarify before gather/model inference.
            candidate_ids = {v["id"] for v in refs.values() if v["kind"] == "incident"}
            ok = paused and set(group["ids"]).issubset(candidate_ids)
            hit = sorted(candidate_ids.intersection(group["ids"]))
        elif kind == "missing":
            details = [i for i in pack.get("incidents", []) if "alerts" in i]
            ok = bool(details) and not any(a.get("samples") for i in details for a in i["alerts"]) and not any(
                c.get("warnings_and_errors") for i in details for c in i.get("service_logs", {}).values())
            hit = []
        else: raise ValueError(f"unknown evidence predicate {kind}")
        checks.append({"id": group["id"], "ok": bool(ok), "hits": hit})
    return checks


def contract_checks(case, answer, cites, events, done, refs):
    route = next((d for e, d in events if e == "route"), {})
    paused = any(e == "questionnaire" for e, _ in events)
    cited_ids = {c["id"] for c in cites if c.get("verified")}
    results = {"completed": done.get("status") == "completed", "route": route.get("route") in case["routes"],
               "citations_resolve": all(c.get("verified") for c in cites), "no_actions": done.get("actions", 0) == 0,
               "behavior": paused if case["behavior"] == "clarify" else not paused}
    # These are executable contract checks. They do NOT substitute for the separate semantic review file.
    if case["behavior"] == "answer" and case["targets"]:
        results["target_citation"] = any(i in cited_ids or any(
            c.get("incident_id") == i for c in cites if c.get("verified")) for i in case["targets"])
    if case["behavior"] == "insufficient":
        results["states_limit"] = bool(re.search(r"不足|无法|不能|未.*(查到|确认)|没有.*(日志|证据|数据)|不确定|待|等待", answer))
    for n, pattern in enumerate(case.get("must_mention", [])):
        results[f"required_text_{n}"] = bool(re.search(pattern, answer, re.I))
    for n, pattern in enumerate(case.get("forbidden", [])):
        results[f"forbidden_text_{n}"] = not re.search(pattern, answer, re.I)
    return results


async def _http_turn(client, session, case, agent, store, observed, model, timeout_s):
    observed.calls.clear(); observed.phase = "prefetch"; model.calls.clear()
    events, timestamps = [], []
    started = time.perf_counter()
    outcome = {"transport_status": "ok"}
    try:
        async with asyncio.timeout(timeout_s):
            async with client.stream("POST", f"/api/v1/chat/sessions/{session}/messages", json={
                    "content": case["question"], "tz": "Asia/Hong_Kong", "thinking": False,
                    "request_id": f"eval-{case['id']}-{time.time_ns()}"}) as response:
                response.raise_for_status()
                event = None
                async for line in response.aiter_lines():
                    if line.startswith("event: "): event = line[7:]
                    elif line.startswith("data: ") and event:
                        events.append((event, json.loads(line[6:])))
                        timestamps.append(round((time.perf_counter()-started)*1000, 3))
                        event = None
    except Exception as e:
        # Retain timeouts and failed runs; never quietly remove them from an attempted denominator.
        outcome = {"transport_status": "timeout" if isinstance(e, TimeoutError) else "error", "error": type(e).__name__}
    done = next((d for e, d in events if e == "done"), {})
    answer = ""
    first_answer = None; first_final = None
    for (name, data), at in zip(events, timestamps):
        if name == "interim": answer = ""; first_final = None
        elif name == "answer" and data.get("delta", "").strip():
            if first_answer is None: first_answer = at
            if first_final is None: first_final = at
            answer += data["delta"]
        elif name == "answer": answer += data.get("delta", "")
    cites = next((d["items"] for e, d in events if e == "citations"), [])
    refs = store.session(session)["refs"]
    pack = first_pack(model.calls)
    checks = evidence_checks(case, pack, refs, events)
    run = store.run(done["run_id"]) if done.get("run_id") else None
    route = next((d for e, d in events if e == "route"), {})
    stream_calls = sum(c["kind"] == "stream" for c in model.calls)
    tools = [c for m in model.calls for c in m.get("tool_calls", [])]
    read_tools = [c for c in tools if c["name"] not in ("ask_user", "propose_action", "propose_memory", "delegate_diagnosis", "update_case")]
    return {"case_id": case["id"], "cohort": case["cohort"], "category": case["category"], "question": case["question"],
            **outcome, "wall_ms": round((time.perf_counter()-started)*1000, 3),
            "http_first_event_ms": timestamps[0] if timestamps else None, "http_first_answer_ms": first_answer,
            "http_first_final_answer_ms": first_final, "done": done, "answer": answer, "citations": cites,
            "route": route, "refs": refs, "events": events, "event_at_ms": timestamps,
            "first_pack": pack, "prefetch_checks": checks, "prefetch_sufficient": all(c["ok"] for c in checks),
            "contracts": contract_checks(case, answer, cites, events, done, refs),
            "extra_answer_model_calls": max(0, stream_calls-1), "answer_model_calls": stream_calls,
            "read_tool_calls": read_tools, "all_tool_calls": tools, "core_calls": observed.calls.copy(),
            "model_calls": copy.deepcopy(model.calls), "run": run,
            "semantic_review": None}


async def evaluate(args):
    settings = Settings(); settings.require_llm()
    # Only this isolated evaluation process bypasses machine proxy settings for localhost.
    old_proxy = os.environ.get("NO_PROXY", "")
    os.environ["NO_PROXY"] = old_proxy+",127.0.0.1,localhost"
    data = json.loads(args.corpus.read_text(encoding="utf-8"))
    cases = load_cases(args.dataset)
    if args.only: cases = [c for c in cases if c["id"] in args.only.split(",")]
    if args.cohort != "all": cases = [c for c in cases if c["cohort"] == args.cohort]
    if not cases: raise ValueError("no cases selected")
    groups = defaultdict(list)
    for case in cases: groups[case["group"]].append(case)
    metadata = {"started_at": datetime.now(timezone.utc).isoformat(), "model": settings.llm_model,
                "fast_model": settings.chat_fast_model or settings.llm_model, "thinking": False,
                "fast_extra_body": settings.chat_fast_extra_body, "temperature": settings.llm_temperature,
                "tool_budget": settings.chat_max_tool_calls, "router_timeout_s": settings.chat_router_timeout_s,
                "casework": False, "writer": False, "corpus_sha256": data["sha256"],
                "dataset_sha256": digest(load_cases(args.dataset)), "repeats": args.repeats, "seed": args.seed,
                "timeout_s": args.timeout, "selected_case_ids": [c["id"] for c in cases],
                "transport": "real localhost TCP HTTP/SSE; live_core uses actual Java/PostgreSQL; controlled uses replay",
                "clock": "fixed per-case evaluation-only clock; source requests retain absolute time bounds",
                "order": "seeded group shuffle; on/off order alternates by group and repetition",
                "cache": "fresh sessions/agent per group/arm; provider prompt cache uncontrolled and metered; no answer cache"}
    args.out.mkdir(parents=True, exist_ok=True)
    path = args.out/"rows.jsonl"
    if path.exists() and path.stat().st_size: raise ValueError("output exists; choose a new directory")
    write(args.out/"manifest.json", metadata)
    original_datetime = agent_module.datetime
    count = 0
    try:
        for repetition in range(args.repeats):
            order = list(groups); random.Random(args.seed+repetition).shuffle(order)
            for group_index, key in enumerate(order):
                group = groups[key]
                arms = (True, False) if (repetition+group_index)%2 == 0 else (False, True)
                for prefetch in arms:
                    case0 = group[0]
                    if case0["cohort"] == "live_core":
                        base = CoreApi(args.core_url, client=httpx.Client(base_url=args.core_url, timeout=15, trust_env=False))
                    else:
                        base = SnapshotCore(scenario(data, case0.get("mutation"), case0.get("mutation_target"))).api()
                    observed = ObservedCore(base)
                    raw_model = OpenAIStreamingChat(settings.llm_base_url, settings.llm_api_key.get_secret_value(),
                        settings.llm_model, fast_model=settings.chat_fast_model, fast_extra_body=settings.chat_fast_extra_body,
                        thinking=False, timeout_s=settings.llm_timeout_s, temperature=settings.llm_temperature)
                    model = ObservedModel(raw_model, observed); store = ChatStore(":memory:")
                    agent = ChatAgent(observed, store, model, max_tool_calls=settings.chat_max_tool_calls,
                        history_turns=settings.chat_history_turns, router_timeout_s=settings.chat_router_timeout_s,
                        prefetch=prefetch, writer=None, casework_on=False)
                    app = create_app(agent, store)
                    sock = socket.socket(); sock.bind(("127.0.0.1", 0)); sock.listen(128); sock.setblocking(False)
                    port = sock.getsockname()[1]
                    server = uvicorn.Server(uvicorn.Config(app, log_level="error", access_log=False, lifespan="off"))
                    task = asyncio.create_task(server.serve(sockets=[sock]))
                    try:
                        while not server.started:
                            if task.done(): await task
                            await asyncio.sleep(.01)
                        async with httpx.AsyncClient(base_url=f"http://127.0.0.1:{port}", timeout=None, trust_env=False) as client:
                            response = await client.post("/api/v1/chat/sessions"); response.raise_for_status()
                            session = response.json()["session_id"]
                            for case in group:
                                fixed = datetime.fromisoformat(case["now"].replace("Z", "+00:00"))
                                class EvaluationClock(datetime):
                                    @classmethod
                                    def now(cls, tz=None): return fixed.astimezone(tz) if tz else fixed.replace(tzinfo=None)
                                agent_module.datetime = EvaluationClock
                                row = await _http_turn(client, session, case, agent, store, observed, model, args.timeout)
                                row.update(prefetch=prefetch, repetition=repetition+1, row_id=f"{case['id']}:{repetition+1}:{int(prefetch)}")
                                with path.open("a", encoding="utf-8") as out:
                                    out.write(json.dumps(row, ensure_ascii=False, default=str)+"\n")
                                count += 1
                                print(f"{count} {row['row_id']} {row['transport_status']} first={row['http_first_answer_ms']}ms "
                                      f"reads={len(row['read_tool_calls'])} sufficient={row['prefetch_sufficient']} "
                                      f"contracts={all(row['contracts'].values())}", flush=True)
                    finally:
                        agent_module.datetime = original_datetime
                        server.should_exit = True; await task
                        await agent.close(); store.close(); base.close(); await raw_model._client.close()
    finally:
        agent_module.datetime = original_datetime
        os.environ["NO_PROXY"] = old_proxy
    metadata["finished_at"] = datetime.now(timezone.utc).isoformat(); metadata["attempts"] = count
    write(args.out/"manifest.json", metadata)


def quantiles(values):
    """Hyndman-Fan type 7 (linear interpolation), explicitly conditional on observed numeric values."""
    values = sorted(v for v in values if v is not None)
    def at(p):
        if not values: return None
        pos = (len(values)-1)*p; lo = math.floor(pos); hi = math.ceil(pos)
        return round(values[lo]+(values[hi]-values[lo])*(pos-lo), 3)
    return {"n": len(values), "median": at(.5), "p90": at(.9), "p95": at(.95), "max": max(values) if values else None}


def rescore(folder: Path, dataset: Path):
    """Correct evidence scoring from immutable traces, without changing measured runs or semantic rubrics."""
    cases = {c['id']: c for c in load_cases(dataset)}
    scores = []
    for line in (folder/'rows.jsonl').read_text(encoding='utf-8').splitlines():
        r = json.loads(line)
        checks = evidence_checks(cases[r['case_id']], r['first_pack'], r['refs'], r['events'])
        scores.append({'row_id': r['row_id'], 'checks': checks, 'sufficient': all(c['ok'] for c in checks),
                       'recorded_sufficient': r['prefetch_sufficient']})
    write(folder/'scores.json', {'version': '1.2', 'dataset_sha256': digest(list(cases.values())),
          'reason': 'incident-scoped sample identity; individual sample-message matching; no model rerun', 'rows': scores})
    return scores


def summarize(folder: Path, review: Path | None = None):
    rows = [json.loads(s) for s in (folder/"rows.jsonl").read_text(encoding="utf-8").splitlines() if s.strip()]
    scoring = json.loads((folder/'scores.json').read_text(encoding='utf-8')) if (folder/'scores.json').exists() else None
    if scoring:
        scores = {s['row_id']: s for s in scoring['rows']}
        if set(scores) != {r['row_id'] for r in rows}: raise ValueError('score/run ids differ')
        for r in rows: r['prefetch_sufficient'] = scores[r['row_id']]['sufficient']
    reviews = {r["row_id"]: r for r in (json.loads(s) for s in review.read_text(encoding="utf-8").splitlines())} if review else {}
    for row in rows:
        checked = reviews.get(row["row_id"])
        if checked and checked.get("answer_sha256") != hashlib.sha256(row["answer"].encode()).hexdigest():
            raise ValueError("review answer hash mismatch: " + row["row_id"])
    tables = {}
    for cohort in sorted({r["cohort"] for r in rows}):
        for prefetch in (True, False):
            chosen = [r for r in rows if r["cohort"] == cohort and r["prefetch"] == prefetch]
            for category in ["all", *sorted({r["category"] for r in chosen})]:
                selected = [r for r in chosen if category == "all" or r["category"] == category]
                reviewed = [r for r in selected if r["row_id"] in reviews]
                correct = [r for r in reviewed if reviews[r["row_id"]]["correct"]]
                numeric = [r for r in selected if r["transport_status"] == "ok" and r["done"].get("status") == "completed"]
                stages = defaultdict(list)
                for r in selected:
                    for span in (r.get("run") or {}).get("usage", {}).get("spans", []):
                        label = "model_"+span.get("purpose", "answer") if span["kind"] == "model" else "step_"+span.get("step_kind", "unknown")
                        stages[label].append(span.get("duration_ms"))
                tables[f"{cohort}/{'on' if prefetch else 'off'}/{category}"] = {
                    "attempts": len(selected), "unique_cases": len({r["case_id"] for r in selected}),
                    "completed": len(numeric), "transport_failures": sum(r["transport_status"] != "ok" for r in selected),
                    "noncompleted": sum(r["done"].get("status") != "completed" for r in selected),
                    "degraded": sum(bool(r["done"].get("degraded")) for r in selected),
                    "core_api_failed_calls": sum(not c.get("ok") for r in selected for c in r["core_calls"]),
                    "questionnaire": sum(bool(r["done"].get("paused")) for r in selected),
                    "prefetch_sufficient": sum(r["prefetch_sufficient"] for r in selected),
                    "route_correct": sum(r["contracts"].get("route", False) for r in selected),
                    "routes": dict(__import__('collections').Counter(r["route"].get("method", "none") for r in selected)),
                    "contract_pass": sum(all(r["contracts"].values()) for r in selected),
                    "semantic_reviewed": len(reviewed), "correct": len(correct),
                    "zero_extra_evidence_and_correct": sum(not r["read_tool_calls"] and not r["extra_answer_model_calls"] for r in correct),
                    "supplemented": sum(bool(r["read_tool_calls"]) for r in selected),
                    "read_tool_requests_total": sum(len(r["read_tool_calls"]) for r in selected),
                    "executed_tools_total": sum(r["done"].get("tool_calls", 0) for r in selected),
                    "extra_answer_model_calls": sum(r["extra_answer_model_calls"] for r in selected),
                    "first_event_ms": quantiles([r["http_first_event_ms"] for r in numeric]),
                    "first_answer_ms": quantiles([r["http_first_answer_ms"] for r in numeric]),
                    "nonpaused_first_answer_ms": quantiles([r["http_first_answer_ms"] for r in numeric if not r["done"].get("paused")]),
                    "first_final_answer_ms": quantiles([r["http_first_final_answer_ms"] for r in numeric]),
                    "complete_ms": quantiles([r["wall_ms"] for r in numeric]),
                    "stage_spans_ms": {k: quantiles(v) for k, v in stages.items()},
                    "source_api_bytes": quantiles([sum(c.get("bytes", 0) for c in r["core_calls"]) for r in selected]),
                    "prefetch_pack_bytes": quantiles([len(json.dumps(r["first_pack"], ensure_ascii=False).encode()) for r in selected if r["first_pack"]]),
                    "cached_tokens_reported": sum(r["done"].get("cached_tokens") or 0 for r in selected)}
    result = {"quantile_algorithm": "Hyndman-Fan type 7; h=(n-1)*p, linear interpolation",
              "evidence_scoring": {k: v for k, v in scoring.items() if k != 'rows'} if scoring else 'as recorded',
              "latency_denominator": "completed runs with observed numeric timing; failed/missing separately counted; not silently excluded",
              "correctness": "requires separate evidence/rubric review; contract_pass is not answer correctness",
              "tables": tables}
    write(folder/"summary.json", result)
    for key, t in tables.items():
        if key.endswith("/all"): print(key, json.dumps({k: v for k, v in t.items() if k not in ('stage_spans_ms',)}, ensure_ascii=False))
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    subs = parser.add_subparsers(dest="command", required=True)
    cap = subs.add_parser("capture"); cap.add_argument("--core-url", default="http://127.0.0.1:8080")
    cap.add_argument("--out", type=Path, default=DEFAULT/"corpus.json"); cap.add_argument("--hours", type=int, default=22)
    run = subs.add_parser("run"); run.add_argument("--corpus", type=Path, default=DEFAULT/"corpus.json")
    run.add_argument("--dataset", type=Path, default=DEFAULT/"cases.jsonl"); run.add_argument("--out", type=Path, required=True)
    run.add_argument("--core-url", default="http://127.0.0.1:8080"); run.add_argument("--repeats", type=int, default=3)
    run.add_argument("--cohort", choices=("all", "live_core", "controlled"), default="all")
    run.add_argument("--only"); run.add_argument("--timeout", type=float, default=120); run.add_argument("--seed", type=int, default=1711)
    summary = subs.add_parser("summarize"); summary.add_argument("folder", type=Path); summary.add_argument("--review", type=Path)
    score = subs.add_parser("rescore"); score.add_argument("folder", type=Path)
    score.add_argument("--dataset", type=Path, default=DEFAULT/'cases.jsonl')
    args = parser.parse_args()
    if args.command == "capture": print(capture(args.core_url, args.out, args.hours)["counts"])
    elif args.command == "run": asyncio.run(evaluate(args))
    elif args.command == "rescore": rescore(args.folder, args.dataset)
    else: summarize(args.folder, args.review)


if __name__ == "__main__": main()
