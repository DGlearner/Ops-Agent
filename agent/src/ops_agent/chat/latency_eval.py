"""How long the chat takes to answer, and whether the answer is right (ops-agent eval-chat), on the live system: run a
drill first (e.g. mysql-down in the lab), then ask about it. Each variant asks the same questions in a fresh session,
in process against core-service, so the numbers leave out only the HTTP hop to the browser:

- thinking on or off: the model's reasoning when it answers (routing has it off either way);
- prefetch on or off: the orchestration fetches the evidence before the model is asked, or the model starts from the
  list of incidents and fetches the rest with its tools, as a plain tool-calling loop would.

Per answer: time to the first event, to the first word of the answer, in all; tool calls; whether the conclusion names
the expected component; citations that did not check out.
"""

import asyncio
import statistics
from dataclasses import dataclass

from ..core_api import CoreApi
from .agent import ChatAgent
from .llm import OpenAIStreamingChat
from .memory import ChatStore


@dataclass(frozen=True)
class Variant:
    thinking: bool
    prefetch: bool

    @property
    def name(self) -> str:
        return f"thinking {'on' if self.thinking else 'off'}, prefetch {'on' if self.prefetch else 'off'}"


VARIANTS = (Variant(True, True), Variant(False, True), Variant(True, False), Variant(False, False))


async def ask_all(agent: ChatAgent, store: ChatStore, questions: list[str], expect: str | None, tz: str) -> list[dict]:
    session = store.create_session()["session_id"]
    results = []
    for question in questions:
        answer, done, cites = "", {}, []
        async for event, data in agent.ask(session, question, tz):
            if event == "answer":
                answer += data["delta"]
            elif event == "interim":
                answer = ""
            elif event == "citations":
                cites = data["items"]
            elif event == "done":
                done = data
        conclusion = answer.split("\n", 1)[0]
        results.append({"question": question, "first_event_ms": done.get("first_event_ms"),
                        "first_answer_ms": done.get("first_answer_ms"), "duration_ms": done.get("duration_ms"),
                        "tool_calls": done.get("tool_calls", 0), "status": done.get("status"),
                        "right": None if expect is None else expect in conclusion,
                        "unverified": sum(1 for c in cites if not c["verified"]), "answer": answer})
    return results


async def evaluate(settings, questions: list[str], runs: int, expect: str | None, tz: str,
                   variants: tuple[Variant, ...] = VARIANTS) -> dict[str, list[dict]]:
    settings.require_llm()
    core = CoreApi(settings.core_url)
    results: dict[str, list[dict]] = {}
    for variant in variants:
        model = OpenAIStreamingChat(settings.llm_base_url, settings.llm_api_key.get_secret_value(), settings.llm_model,
                                    fast_model=settings.chat_fast_model, fast_extra_body=settings.chat_fast_extra_body,
                                    thinking=variant.thinking, timeout_s=settings.llm_timeout_s,
                                    temperature=settings.llm_temperature)
        store = ChatStore(":memory:")
        agent = ChatAgent(core, store, model, max_tool_calls=settings.chat_max_tool_calls,
                          history_turns=settings.chat_history_turns, prefetch=variant.prefetch)
        rows = []
        for _ in range(runs):
            rows += await ask_all(agent, store, questions, expect, tz)
        results[variant.name] = rows
        report(variant.name, rows)
    return results


def report(name: str, rows: list[dict]) -> None:
    def median(key: str) -> str:
        values = [r[key] for r in rows if r[key] is not None]
        return f"{statistics.median(values) / 1000:.1f} s" if values else "—"

    right = [r["right"] for r in rows if r["right"] is not None]
    print(f"{name}: {len(rows)} answers; first word {median('first_answer_ms')} (median), in all "
          f"{median('duration_ms')}; tool calls {statistics.mean(r['tool_calls'] for r in rows):.1f} on average"
          + (f"; right {sum(right)}/{len(right)}" if right else "")
          + f"; unverified citations {sum(r['unverified'] for r in rows)}; "
          + f"not completed {sum(1 for r in rows if r['status'] != 'completed')}")
    for r in rows:
        print(f"    {r['question']}: first word {r['first_answer_ms']} ms, in all {r['duration_ms']} ms, "
              f"{r['tool_calls']} tool calls, right {r['right']}; {r['answer'].splitlines()[0][:90] if r['answer'] else ''}")


def main(settings, questions: list[str], runs: int, expect: str | None, tz: str, only: str | None) -> None:
    variants = tuple(v for v in VARIANTS if only is None or v.name == only) or VARIANTS
    asyncio.run(evaluate(settings, questions, runs, expect, tz, variants))
