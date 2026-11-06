"""Outcome evaluation on the live lab (docs/agent-design.md §9.4): did handling the fault make it recover, how fast,
and was anything done that did not help? This is what tells an agent that handles incidents from one that answers
questions about them.

For each scenario: the lab console starts traffic and injects the fault (it alone keeps the answer key); a scripted
on-call person opens the incident in the assistant and asks it to handle it, confirms a remediation card that names
the preconditions it was proposed on and declines anything else, then waits for the verification. The scores use the
answer key only afterwards. The person's policy is fixed and never looks at the answer key. Whether the assistant
runs with a model or without (runbook-only, the baseline) is the chat service's own setting; the report names it.

Each injection starts from quiet: nothing open and no alert for longer than the alert windows, otherwise the last
fault's aftermath gets scored as this one's detection, and an incident left open swallows every later fault. A round
can come out differently from the last (what is in the logs when the assistant looks is a race), so --rounds repeats
them and the report counts per scenario; results are written after every scenario, so a stopped run keeps what it has.
"""

import json
import logging
import time
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path

import httpx

log = logging.getLogger(__name__)

ASK = "下单失败了，帮我处理一下"
# What a remediation acts on, against the lab's target of the fault.
ADDRESSES = {"start_container": lambda p: p.get("name"), "kill_transaction": lambda p: "shop-mysql",
             "set_switch": lambda p: {"payment.fast-fail": "payment-service",
                                      "inventory.fast-fail": "inventory-service"}.get(p.get("name"))}
RELATED = {"shop-mysql": {"shop-mysql"}, "inventory-service": {"inventory-service", "shop-mysql"},
           "payment-service": {"payment-service"}}
# order-service's management port: how the on-call person turns a switch back off.
ORDER_MANAGEMENT = "http://127.0.0.1:8191"
# Longer than the rules' one-minute windows: an alert for the last fault can only arrive within one of them.
QUIET_S = 90
SETTLE_TIMEOUT_S = 300


def main(scenarios: list[str], chat_url: str, lab_url: str, core_url: str, out: Path | None,
         detect_timeout_s: float = 120, verify_timeout_s: float = 300, rounds: int = 1) -> list[dict]:
    lab = httpx.Client(base_url=lab_url, timeout=30)
    chat = httpx.Client(base_url=chat_url, timeout=180)
    core = httpx.Client(base_url=core_url, timeout=30)
    mode = chat.get("/api/v1/chat/health").json().get("model") or "none (runbook-only baseline)"
    _switches_off(core)  # an earlier run's may still be on, and would hide the faults behind it
    lab.post("/api/lab/traffic", json={"rate": 5})
    time.sleep(20)
    results = []
    try:
        for number in range(1, rounds + 1):
            for name in scenarios:
                settled = _settle(core)
                result = _scenario(name, lab, chat, core, detect_timeout_s, verify_timeout_s)
                results.append({**result, "round": number, "settled": settled})
                _write(out, _report(mode, results))
    finally:
        lab.delete("/api/lab/traffic")
    report = _report(mode, results)
    print(_text(report))
    if out:
        print(f"结果写入 {out}")
    return results


def _report(mode: str, results: list[dict]) -> dict:
    return {"model": mode, "at": datetime.now(timezone.utc).isoformat(), "scenarios": results,
            "summary": _summary(results), "by_scenario": _by_scenario(results)}


def _write(out: Path | None, report: dict) -> None:
    if out:
        out.write_text(json.dumps(report, ensure_ascii=False, indent=1), encoding="utf-8")


def _settle(core, quiet_s: float = QUIET_S, timeout_s: float = SETTLE_TIMEOUT_S) -> dict:
    """Closes what is open and waits for the alerts to stop. Says so when they did not: that round's detection cannot
    be told from the last fault's aftermath, and the report must not count it as clean."""
    started = time.monotonic()
    closed: list[str] = []
    while True:
        for incident in core.get("/api/v1/incidents", params={"status": "open", "limit": 200}).json()["items"]:
            core.post(f"/api/v1/incidents/{incident['incident_id']}/transitions",
                      json={"to_status": "resolved", "note": "结果评测：注入前清场"})
            closed.append(incident["incident_id"])
        recent = core.get("/api/v1/alerts", params={"from": _iso(datetime.now(timezone.utc) - timedelta(seconds=quiet_s)),
                                                    "limit": 1}).json()["items"]
        waited = time.monotonic() - started
        if not recent:
            return {"quiet": True, "waited_s": round(waited), "closed": closed}
        if waited >= timeout_s:
            return {"quiet": False, "waited_s": round(waited), "closed": closed}
        time.sleep(5)


def _scenario(name: str, lab, chat, core, detect_timeout_s: float, verify_timeout_s: float) -> dict:
    episode = lab.post(f"/api/lab/faults/{name}", json={"duration_s": 300}).json()
    injected = datetime.fromisoformat(episode["started_at"].replace("Z", "+00:00")) if episode.get(
        "started_at") else datetime.now(timezone.utc)
    result: dict = {"scenario": name, "target": episode.get("target"), "injected_at": injected.isoformat(),
                    "detected": False, "cards": [], "confirmed": [], "declined": []}
    try:
        incident = _await_incident(core, injected, detect_timeout_s)
        if incident is None:
            result["absorbed_by"] = _absorbing(core, injected)
            return result
        result.update(detected=True, incident_id=incident["incident_id"],
                      detected_after_s=_since(injected, incident["opened_at"]))
        session = chat.post("/api/v1/chat/sessions").json()["session_id"]
        card = None
        for attempt in range(3):
            events = _ask(chat, session, ASK, incident["incident_id"])
            done = next((d for e, d in events if e == "done"), {})
            result.setdefault("runs", []).append({"run_id": done.get("run_id"), "run_status": done.get("run_status"),
                                                  "case_id": done.get("case_id"),
                                                  "steps": [d.get("title") for e, d in events if e == "step"
                                                            and d.get("status") == "done"][-6:]})
            cards = [d for e, d in events if e == "action"]
            result["cards"] += [{"kind": c["kind"], "params": c["params"], "remedial": bool(c.get("remedial"))}
                                for c in cards]
            card = next((c for c in cards if c.get("remedial") and c.get("preconditions")), None)
            for other in cards:
                if other is not card:
                    chat.post(f"/api/v1/chat/sessions/{session}/actions/{other['action_id']}/decline",
                              json={"version": other["version"]})
                    result["declined"].append(other["kind"])
            if card:
                result["case_id"] = done.get("case_id")
                result["run_id"] = done.get("run_id")
                break
            time.sleep(20)
        if card is None:
            return result
        result["card_after_s"] = (datetime.now(timezone.utc) - injected).total_seconds()
        confirmed = chat.post(f"/api/v1/chat/sessions/{session}/actions/{card['action_id']}/confirm",
                              json={"version": card["version"], "payload_digest": card["payload_digest"]}).json()
        receipt = confirmed.get("receipt") or {}
        result["confirmed"].append({"kind": card["kind"], "params": card["params"], "receipt": receipt.get("status"),
                                    "summary": receipt.get("summary")})
        result["executed_after_s"] = (datetime.now(timezone.utc) - injected).total_seconds()
        target = ADDRESSES.get(card["kind"], lambda p: None)(card["params"])
        result["addresses_target"] = target in RELATED.get(result["target"] or "", {result["target"]})
        verification = _await_verification(core, result["case_id"], verify_timeout_s)
        if verification:
            result.update(verification=verification["outcome"], goal=verification["criteria"].get("goal", "recover"),
                          verified_after_s=_since(injected, verification["completed_at"]),
                          checks=[{"id": c["id"], "verdict": c["verdict"], "reason": c["reason"]}
                                  for c in (verification.get("result") or {}).get("checks", [])])
            case = core.get(f"/api/v1/cases/{result['case_id']}").json()
            result["case_status"] = case["status"]
            chain = chat.get(f"/api/v1/chat/runs/{result['run_id']}")
            result["chain_problems"] = (chain_problems(chain.json(), card["action_id"]) if chain.status_code == 200
                                        else [f"HTTP {chain.status_code}"])
    finally:
        lab.delete(f"/api/lab/faults/{name}")
        _sign_off(core, result)
        result["switches_turned_off"] = _switches_off(core)
    return result


def chain_problems(chain: dict, action_id: str) -> list[str]:
    """What is missing on the way from the run to the verdict (docs/agent-design.md §15.2); empty: it holds together."""
    run = chain["run"]
    card = next((a for a in chain["actions"] if a["action_id"] == action_id), None)
    if card is None:
        return ["卡片不在这一轮里"]
    done = card.get("remediation")
    if done is None:
        return ["卡片没有对应的处置"]
    problems = []
    if done.get("run_id") != run["run_id"]:
        problems.append(f"处置的 run_id 是 {done.get('run_id')}")
    if done.get("snapshot_digest") != run["snapshot_digest"] or not run["snapshot_digest"]:
        problems.append("处置的快照摘要和运行记录不一致")
    verifications = [v for v in card.get("verifications") or [] if v["status"] == "done"]
    if not verifications:
        problems.append("处置没有做完的验证")
    elif any(v.get("run_id") != run["run_id"] for v in verifications):
        problems.append("验证的 run_id 不一致")
    if not (run.get("usage") or {}).get("spans"):
        problems.append("运行记录没有用量")
    return problems


def _switches_off(core) -> list[str]:
    """A switch turned on to contain a fault outlives the scenario (until it expires), and a fast-fail on one
    dependency keeps orders from ever reaching the next: the next scenario would see nothing."""
    turned = [s["name"] for s in core.get("/api/v1/lab/switches").json()["items"] if s["on"]]
    for name in turned:
        httpx.post(f"{ORDER_MANAGEMENT}/actuator/switches", json={"name": name, "on": False}, timeout=10
                   ).raise_for_status()
    return turned


def _sign_off(core, result: dict) -> None:
    """The on-call person closes the scenario: signs the case off and resolves the incident, so that the next
    fault is a new incident rather than more alerts on this one. Recorded, since a person did it."""
    closed = []
    if result.get("case_id"):
        case = core.get(f"/api/v1/cases/{result['case_id']}").json()
        if case["status"] != "resolved":
            r = core.post(f"/api/v1/cases/{case['case_id']}/transitions",
                          json={"to_status": "resolved", "actor": "user", "expected_version": case["version"],
                                "note": "结果评测：场景结束，人工验收"})
            closed.append(f"case {r.status_code}")
    if result.get("incident_id"):
        r = core.post(f"/api/v1/incidents/{result['incident_id']}/transitions",
                      json={"to_status": "resolved", "note": "结果评测：场景结束"})
        closed.append(f"incident {r.status_code}")
    result["signed_off"] = closed


def _ask(chat, session: str, question: str, incident_id: str) -> list[tuple[str, dict]]:
    body = {"content": question, "tz": "Asia/Shanghai", "request_id": str(uuid.uuid4()),
            "page": {"incident_id": incident_id}}
    events, name = [], None
    with chat.stream("POST", f"/api/v1/chat/sessions/{session}/messages", json=body) as response:
        for line in response.iter_lines():
            if line.startswith("event:"):
                name = line[6:].strip()
            elif line.startswith("data:") and name:
                events.append((name, json.loads(line[5:].strip())))
    return events


def _await_incident(core, since: datetime, timeout_s: float) -> dict | None:
    """The first incident opened after the injection. One opened before it is not this fault's detection."""
    deadline = time.monotonic() + timeout_s
    while time.monotonic() < deadline:
        items = core.get("/api/v1/incidents", params={"from": _iso(since)}).json()["items"]
        active = [i for i in items if i["status"] not in ("resolved", "closed") and _since(since, i["opened_at"]) >= 0]
        if active:
            return min(active, key=lambda i: i["opened_at"])
        time.sleep(3)
    return None


def _absorbing(core, since: datetime) -> list[str]:
    """Incidents opened earlier and still taking alerts: the fault was seen, and went onto one of these."""
    items = core.get("/api/v1/incidents", params={"status": "open", "limit": 200}).json()["items"]
    return [i["incident_id"] for i in items if _since(since, i["last_alert_at"]) >= 0]


def _await_verification(core, case_id: str, timeout_s: float) -> dict | None:
    deadline = time.monotonic() + timeout_s
    while time.monotonic() < deadline:
        done = [v for v in core.get("/api/v1/verifications", params={"case_id": case_id}).json()["items"]
                if v["status"] == "done"]
        if done:
            return done[-1]
        time.sleep(5)
    return None


def _summary(results: list[dict]) -> dict:
    n = len(results)
    # A verification met with the goal "contain" stopped the damage; the fault is still there.
    verified = [r for r in results if r.get("verification") == "recovered"]
    contained = [r for r in verified if r.get("goal") == "contain"]
    return {"scenarios": n, "detected": sum(r["detected"] for r in results),
            "card_proposed": sum(1 for r in results if r["confirmed"]),
            "addresses_target": sum(1 for r in results if r.get("addresses_target")),
            "recovered": len(verified) - len(contained), "contained": len(contained),
            "unneeded_confirmed": sum(1 for r in results if r["confirmed"] and not r.get("addresses_target")),
            "chain_complete": sum(1 for r in results if r.get("chain_problems") == []),
            "absorbed": sum(1 for r in results if r.get("absorbed_by")),
            "not_quiet_at_start": sum(1 for r in results if not (r.get("settled") or {}).get("quiet", True)),
            "median_verified_after_s": _median([r["verified_after_s"] for r in verified])}


def _by_scenario(results: list[dict]) -> dict:
    """Per scenario across the rounds: how often each thing happened, since one round says little."""
    found: dict[str, dict] = {}
    for r in results:
        s = found.setdefault(r["scenario"], {"rounds": 0, "detected": 0, "absorbed": 0, "cards": {}, "verifications": {}})
        s["rounds"] += 1
        s["detected"] += 1 if r["detected"] else 0
        s["absorbed"] += 1 if r.get("absorbed_by") else 0
        for card in r["confirmed"]:
            s["cards"][card["kind"]] = s["cards"].get(card["kind"], 0) + 1
        if r.get("verification"):
            key = "contained" if r["verification"] == "recovered" and r.get("goal") == "contain" else r["verification"]
            s["verifications"][key] = s["verifications"].get(key, 0) + 1
    return found


def _text(report: dict) -> str:
    lines = [f"结果评测（{report['model']}）"]
    for r in report["scenarios"]:
        lines.append(f"- {'第 ' + str(r['round']) + ' 轮 ' if r.get('round') else ''}"
                     f"{r['scenario']}（目标 {r['target']}）：检出 {'是' if r['detected'] else '否'}"
                     + ("（告警并入了注入前就开着的事件，这一轮不算数）" if r.get("absorbed_by") else "")
                     + ("（注入前没能静下来，这一轮不算干净）" if not (r.get("settled") or {}).get("quiet", True) else "")
                     + (f"，{r.get('detected_after_s', 0):.0f} s" if r["detected"] else "")
                     + (f"；确认 {r['confirmed'][0]['kind']} {json.dumps(r['confirmed'][0]['params'], ensure_ascii=False)}"
                        f"（回执 {r['confirmed'][0]['receipt']}，{'对准' if r.get('addresses_target') else '没对准'}目标）"
                        if r["confirmed"] else "；没有可确认的处置")
                     + (f"；验证 {r['verification']}{'（止血，故障还在）' if r.get('goal') == 'contain' else ''}，"
                        f"注入后 {r['verified_after_s']:.0f} s" if r.get("verification") else ""))
    s = report["summary"]
    lines.append(f"合计：{s['scenarios']} 个场景，检出 {s['detected']}，提出并确认处置 {s['card_proposed']}，"
                 f"对准目标 {s['addresses_target']}，恢复 {s['recovered']}，止血 {s['contained']}，不必要的处置 {s['unneeded_confirmed']}，"
                 f"链路完整 {s['chain_complete']}，验证通过用时中位数 {s['median_verified_after_s']} s")
    if any(r.get("round", 1) > 1 for r in report["scenarios"]):
        for name, b in report["by_scenario"].items():
            lines.append(f"  {name}：{b['rounds']} 轮，检出 {b['detected']}，处置 {b['cards']}，验证 {b['verifications']}")
    return "\n".join(lines)


def _since(start: datetime, value: str) -> float:
    return (datetime.fromisoformat(value.replace("Z", "+00:00")) - start).total_seconds()


def _median(values: list[float]) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    mid = len(ordered) // 2
    return round(ordered[mid] if len(ordered) % 2 else (ordered[mid - 1] + ordered[mid]) / 2, 1)


def _iso(t: datetime) -> str:
    return t.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")
