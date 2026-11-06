"""What one run spent, and where the time went (docs/agent-design.md §15.1).

A Ledger keeps the run's spans: each model call (tier, purpose, time to the first token, tokens) and each step of the
graph. Metered wraps the model for one run, so that nothing else has to remember to count. The ledger says what is
known and no more: a call the provider reported no usage for is counted as such rather than as zero tokens, and a span
still open when the run ends was cut off.
"""

import asyncio
import time
from collections.abc import Callable, Iterator
from contextlib import contextmanager
from typing import Any

from .llm import Delta, StreamingModel, Turn

MODEL, STEP = "model", "step"
TOKENS = {"prompt": "prompt_tokens", "completion": "completion_tokens", "cached": "cached_tokens",
          "reasoning": "reasoning_tokens"}


class Ledger:
    def __init__(self, clock: Callable[[], float] = time.monotonic):
        self._clock = clock
        self._started = clock()
        self.spans: list[dict] = []

    def begin(self, kind: str, name: str, **fields: Any) -> dict:
        span = {"kind": kind, "name": name, "at_ms": self.elapsed_ms(), "duration_ms": None, "status": "running",
                **fields}
        self.spans.append(span)
        return span

    def end(self, span: dict, status: str = "ok", **fields: Any) -> None:
        span.update(duration_ms=self.elapsed_ms() - span["at_ms"], status=status, **fields)

    def settle(self) -> None:
        """The run is over: whatever is still open was cut off."""
        for span in self.spans:
            if span["status"] == "running":
                self.end(span, "cancelled")

    def summary(self) -> dict:
        model = [s for s in self.spans if s["kind"] == MODEL]
        tools = [s for s in self.spans if s["kind"] == STEP and s.get("step_kind") == "tool"]
        reported = [s for s in model if s.get("prompt_tokens") is not None or s.get("completion_tokens") is not None]

        def total(key: str) -> int | None:
            values = [s[key] for s in reported if s.get(key) is not None]
            return sum(values) if values else None

        prompt, completion = total("prompt_tokens"), total("completion_tokens")
        return {"model_calls": len(model), "model_ms": _spent(model), "prompt_tokens": prompt,
                "completion_tokens": completion, "cached_tokens": total("cached_tokens"),
                "reasoning_tokens": total("reasoning_tokens"),
                "total_tokens": (prompt or 0) + (completion or 0) if reported else None,
                "calls_without_usage": len(model) - len(reported), "tool_steps": len(tools), "tool_ms": _spent(tools)}

    def as_dict(self) -> dict:
        return {**self.summary(), "wall_ms": self.elapsed_ms(), "spans": self.spans}

    def elapsed_ms(self) -> int:
        return int((self._clock() - self._started) * 1000)


class Metered:
    """The model as one run sees it: the same calls, each one in the ledger."""

    def __init__(self, model: StreamingModel, ledger: Ledger, purpose: str = "answer"):
        self._model = model
        self._ledger = ledger
        self._purpose = purpose
        self.name = model.name

    def for_purpose(self, purpose: str) -> "Metered":
        return Metered(self._model, self._ledger, purpose)

    async def stream(self, messages: list[dict], tools: list[dict] | None = None, *,
                     on_reasoning: Delta | None = None, on_content: Delta | None = None,
                     thinking: bool | None = None) -> Turn:
        with self._call("main") as span:
            turn = await self._model.stream(messages, tools, on_reasoning=self._timed(span, on_reasoning),
                                            on_content=self._timed(span, on_content), thinking=thinking)
            _note_usage(span, turn.usage)
            return turn

    async def quick(self, messages: list[dict], *, json_output: bool = False, timeout_s: float | None = None,
                    on_content: Delta | None = None) -> str:
        with self._call("quick") as span:
            return await self._model.quick(messages, json_output=json_output, timeout_s=timeout_s,
                                           on_content=self._timed(span, on_content),
                                           on_usage=lambda usage: _note_usage(span, usage))

    @contextmanager
    def _call(self, tier: str) -> Iterator[dict]:
        span = self._ledger.begin(MODEL, self.name, tier=tier, purpose=self._purpose)
        try:
            yield span
        except asyncio.CancelledError:
            self._ledger.end(span, "cancelled")
            raise
        except Exception as e:
            self._ledger.end(span, "error", error=type(e).__name__)
            raise
        self._ledger.end(span)

    def _timed(self, span: dict, on_delta: Delta | None) -> Delta | None:
        """None stays None: a quick call without a callback is not streamed at all."""
        if on_delta is None:
            return None

        def timed(delta: str) -> None:
            if "first_token_ms" not in span:
                span["first_token_ms"] = self._ledger.elapsed_ms() - span["at_ms"]
            on_delta(delta)
        return timed


def _note_usage(span: dict, usage: dict[str, int]) -> None:
    for key, field in TOKENS.items():
        if usage.get(key) is not None:
            span[field] = usage[key]


def _spent(spans: list[dict]) -> int:
    return sum(s["duration_ms"] or 0 for s in spans)
