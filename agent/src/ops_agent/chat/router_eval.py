"""How well the router routes (ops-agent eval-router). Two sets of labeled questions in agent/eval/, some of them
follow-ups with a conversation before them: router_dev.jsonl, written with the rules and used to tune them, and
router_holdout.jsonl, written afterwards and run once before the rules were changed (docs/project-design.md §6.7).
Each is answered three ways:

- rules: only the rules, their guess where they are not sure;
- model: only the model, for every question;
- two levels: what the chat does, the rules where they are sure and the model for the rest.

For each: accuracy, how many questions the rules decided, and the time per question. A case may accept more than one
route when either is a fair reading ("那支付服务呢" after a diagnosis)."""

import asyncio
import json
import statistics
import time
from datetime import datetime, timezone
from pathlib import Path

from .router import OPS_ROUTES, Router, match_rules

EVAL = Path(__file__).resolve().parents[3] / "eval"
SETS = (EVAL / "router_dev.jsonl", EVAL / "router_holdout.jsonl")
CATALOG = {"order-service", "inventory-service", "payment-service", "shop-mysql", "shop-redis"}
FOCUS = {"incidents": [{"incident_id": "0199f000-0000-7000-8000-000000000011", "ref": "I1",
                        "title": "order-service answered 12 requests with DATABASE_UNAVAILABLE"}]}


def load(path: Path) -> list[dict]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def rules_only(cases: list[dict], now: datetime) -> dict:
    sure = correct = sure_correct = 0
    wrong = []
    for case in cases:
        match = match_rules(case["question"], now, CATALOG, has_focus=case.get("focus", False))
        ok = match.route in case["accept"]
        correct += ok
        if match.sure:
            sure += 1
            sure_correct += ok
            if not ok:
                wrong.append((case["question"], match.route, case["accept"]))
    return {"accuracy": correct / len(cases), "decided": sure / len(cases),
            "precision_when_sure": sure_correct / sure if sure else 0.0, "wrong_when_sure": wrong}


async def with_model(router: Router, cases: list[dict], now: datetime, *, two_levels: bool) -> dict:
    correct = failed = by_rules = ops_correct = 0
    times = []
    wrong = []
    for case in cases:
        focus = FOCUS if case.get("focus") else {}
        history = case.get("history", [])
        started = time.monotonic()
        if two_levels:
            decision = await router.decide(case["question"], now=now, catalog=CATALOG, focus=focus, history=history)
            route, method = decision.route, decision.method
            by_rules += method == "rule"
            failed += method == "fallback"
        else:
            try:
                route = (await router.classify(case["question"], now=now, catalog=CATALOG, focus=focus,
                                               history=history)).route
            except Exception:  # noqa: BLE001 - counted as a miss
                route, failed = None, failed + 1
        times.append((time.monotonic() - started) * 1000)
        # Diagnosis and status questions take the same orchestration: mixing them up costs only the answer's form.
        ops_correct += (route in OPS_ROUTES) == any(r in OPS_ROUTES for r in case["accept"])
        if route in case["accept"]:
            correct += 1
        else:
            wrong.append((case["question"], route, case["accept"]))
    return {"accuracy": correct / len(cases), "ops_or_not": ops_correct / len(cases),
            "decided_by_rules": by_rules / len(cases), "model_failures": failed,
            "ms_mean": statistics.mean(times), "ms_p50": statistics.median(times),
            "ms_max": max(times), "wrong": wrong}


def report(name: str, result: dict) -> None:
    numbers = ", ".join(f"{k} {v:.1%}" if isinstance(v, float) and k not in ("ms_mean", "ms_p50", "ms_max")
                        else f"{k} {v:.0f}" if isinstance(v, float) else f"{k} {v}"
                        for k, v in result.items() if not isinstance(v, list))
    print(f"{name}: {numbers}")
    for question, route, accept in result.get("wrong_when_sure", []) + result.get("wrong", []):
        print(f"    {question!r}: {route}, expected {' or '.join(accept)}")


def main(router: Router | None, sets: tuple[Path, ...] = SETS) -> None:
    # One event loop for all of it: the model's HTTP client keeps its connections on the loop it started on.
    asyncio.run(_main(router, sets))


async def _main(router: Router | None, sets: tuple[Path, ...]) -> None:
    now = datetime.now(timezone.utc)
    for path in sets:
        cases = load(path)
        print(f"{path.name}: {len(cases)} questions, {sum(1 for c in cases if c.get('focus'))} of them follow-ups")
        report("  rules", rules_only(cases, now))
        if router is not None:
            report("  model", await with_model(router, cases, now, two_levels=False))
            report("  two levels", await with_model(router, cases, now, two_levels=True))
    if router is None:
        print("no model configured: only the rules were evaluated")
