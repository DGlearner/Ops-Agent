"""When the assistant asks before it answers (docs/project-design.md §6.7, 追问).

Two ways to a questionnaire; both end the answer at a pause point that the next message goes on from:
- the orchestration: a diagnosis that names no incident and no component finds incidents in its window that the rules
  put on different components. Picking one would answer about the wrong fault, so the user picks (or "都看看").
  Lists and comparisons ("有几个问题""是同一个原因吗") are not asked about: all of them is the answer there, and so
  are incidents that point at the same component (two MySQL incidents are one problem to the user).
- the model, with ask_user: it needs something no tool can tell (what the user saw, what they tried). At most once
  per question, and never after a resume, so a conversation cannot go round in questions.

A questionnaire has 1-4 questions, each single or multiple choice with up to 5 options, and room for the user's own
words where allow_text. The pause keeps only what is needed to go on: the question, its route and entities, which
option stands for which incident. Not the data: that is fetched again on resume, as it is by then. Answering is a
compare-and-set from open (memory.ChatStore.close_pause), so one answer counts however often it is sent; a new
question instead of an answer supersedes the pause.
"""

import re
from datetime import datetime, timedelta, timezone

from ..baseline import rule_attribution
from .router import Decision

MAX_QUESTIONS = 4
MAX_OPTIONS = 5
MAX_TEXT = 200
PAUSE_TTL = timedelta(minutes=30)
ALL = "all"
# The question is about more than one thing, or about how several relate: no need to pick.
PLURAL = re.compile(r"几个|哪些|都|所有|全部|同一个|分别|各个|多个|这些|一起|相关|有关系|列出|汇总")


class AnswerError(ValueError):
    """The answers do not fit the questionnaire; said back to the user as a 400."""


def target_questionnaire(question: str, decision: Decision, incidents: list[dict], refs_of, span: str) -> dict | None:
    """The orchestration's question, or None: answer straight away. refs_of(incident) gives the incident's ref."""
    found = decision.entities
    if (decision.route != "ops_diagnosis" or found.incident_ids or found.follow_up or found.services
            or len(incidents) < 2 or PLURAL.search(question)):
        return None
    candidates = incidents[:MAX_OPTIONS - 1]
    pointed = [_pointed(i) for i in candidates]
    if len({p for p in pointed if p}) < 2:
        return None
    options = []
    for incident, component in zip(candidates, pointed):
        ref = refs_of(incident)
        options.append({"id": ref, "label": f"[{ref}] {incident['title'][:60]}",
                        "detail": (f"规则指向 {component} · " if component else "") + f"最后一条告警 {incident['last_alert_at']}",
                        "incident_id": incident["incident_id"]})
    options.append({"id": ALL, "label": "都看看", "detail": "逐个分析，并说明它们是否相关"})
    return {"source": "orchestration", "questions": [{
        "id": "target", "title": f"{span}有 {len(candidates)} 个指向不同组件的故障，你问的是哪个？", "kind": "single",
        "options": options, "allow_text": False}]}


def model_questionnaire(args: dict) -> dict:
    """The model's ask_user, checked: 1-4 questions with unique ids and at most 5 options each."""
    questions = args.get("questions")
    if not isinstance(questions, list) or not 1 <= len(questions) <= MAX_QUESTIONS:
        raise ValueError(f"questions 要有 1 到 {MAX_QUESTIONS} 个")
    shown, ids = [], set()
    for n, q in enumerate(questions, 1):
        if not isinstance(q, dict) or not str(q.get("title") or "").strip():
            raise ValueError(f"第 {n} 个问题没有 title")
        qid = str(q.get("id") or f"q{n}")[:20]
        if qid in ids:
            raise ValueError(f"问题 id {qid} 重复")
        ids.add(qid)
        options = q.get("options") or []
        if not isinstance(options, list) or len(options) > MAX_OPTIONS:
            raise ValueError(f"每个问题最多 {MAX_OPTIONS} 个选项")
        labels = [str(o)[:60] for o in options if str(o).strip()]
        if len(set(labels)) != len(labels):
            raise ValueError("选项重复")
        allow_text = bool(q.get("allow_text", True)) or not labels
        shown.append({"id": qid, "title": str(q["title"]).strip()[:80],
                      "kind": "multiple" if q.get("multiple") else "single",
                      "options": [{"id": f"o{m}", "label": label} for m, label in enumerate(labels, 1)],
                      "allow_text": allow_text})
    return {"source": "model", "questions": shown}


def pause_record(questionnaire: dict, *, pause_id: str, session_id: str, message_id: str, question: str,
                 decision: Decision, now: datetime | None = None) -> dict:
    now = now or datetime.now(timezone.utc)
    return {"pause_id": pause_id, "session_id": session_id, "message_id": message_id,
            "questions": questionnaire["questions"],
            "state": {"source": questionnaire["source"], "question": question, "decision": decision.as_dict()},
            "created_at": _iso(now), "expires_at": _iso(now + PAUSE_TTL)}


def read_answers(pause: dict, answers: list[dict]) -> dict:
    """What the answers mean for going on: the incidents picked (if any), and the answers in words, for the user's
    message and for the model. Every choice must be one of the question's options."""
    given = {}
    for a in answers or []:
        if not isinstance(a, dict) or "id" not in a:
            raise AnswerError("每个回答要有问题的 id")
        given[str(a["id"])] = a
    unknown = set(given) - {q["id"] for q in pause["questions"]}
    if unknown:
        raise AnswerError(f"没有这些问题：{'、'.join(sorted(unknown))}")
    incident_ids: list[str] | None = None
    lines = []
    for q in pause["questions"]:
        a = given.get(q["id"])
        if a is None:
            continue
        options = {o["id"]: o for o in q["options"]}
        choices = [str(c) for c in a.get("choices") or []]
        if any(c not in options for c in choices):
            raise AnswerError(f"「{q['title']}」没有这个选项")
        if q["kind"] == "single" and len(choices) > 1:
            raise AnswerError(f"「{q['title']}」只能选一个")
        text = str(a.get("text") or "").strip()
        if text and not q["allow_text"]:
            raise AnswerError(f"「{q['title']}」只能选选项")
        if len(text) > MAX_TEXT:
            raise AnswerError(f"回答最多 {MAX_TEXT} 字")
        if not choices and not text:
            continue
        if q["id"] == "target" and pause["state"]["source"] == "orchestration":
            picked = [o for o in q["options"] if o.get("incident_id")] if ALL in choices else \
                [options[c] for c in choices]
            incident_ids = [o["incident_id"] for o in picked]
        said = "、".join(options[c]["label"] for c in choices) + ("；" if choices and text else "") + text
        lines.append(f"{q['title']} {said}")
    if not lines:
        raise AnswerError("至少回答一个问题")
    return {"incident_ids": incident_ids, "lines": lines}


def expired(pause: dict, now: datetime | None = None) -> bool:
    now = now or datetime.now(timezone.utc)
    return now >= datetime.fromisoformat(pause["expires_at"].replace("Z", "+00:00"))


def _pointed(incident: dict) -> str | None:
    hypotheses = rule_attribution(incident, [])
    return hypotheses[0].component if hypotheses else None


def _iso(t: datetime) -> str:
    return t.astimezone(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")
