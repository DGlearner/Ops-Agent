"""The model behind the chat: any OpenAI-compatible chat completions API, streamed. Reasoning models send their
thinking first (reasoning_content), then the answer or tool calls; both are handed on as they arrive."""

import json
import logging
from collections.abc import Callable
from dataclasses import dataclass, field
from typing import Any, Protocol

import openai

from ..llm import ToolCall

log = logging.getLogger(__name__)

Delta = Callable[[str], None]


@dataclass
class Turn:
    """One streamed model turn, put together."""
    content: str = ""
    reasoning: str = ""
    tool_calls: list[ToolCall] = field(default_factory=list)
    usage: dict[str, int] = field(default_factory=dict)

    def as_message(self) -> dict:
        message: dict[str, Any] = {"role": "assistant", "content": self.content}
        if self.reasoning:
            # Reasoning models that call tools want their thinking back with the turn.
            message["reasoning_content"] = self.reasoning
        if self.tool_calls:
            message["tool_calls"] = [{"id": c.id, "type": "function",
                                      "function": {"name": c.name, "arguments": c.arguments}}
                                     for c in self.tool_calls]
        return message


class StreamingModel(Protocol):
    name: str

    async def stream(self, messages: list[dict], tools: list[dict] | None = None, *,
                     on_reasoning: Delta | None = None, on_content: Delta | None = None) -> Turn:
        """One turn of the main model, streamed to the callbacks as it arrives."""
        ...

    async def quick(self, messages: list[dict], *, json_output: bool = False, timeout_s: float | None = None,
                    on_content: Delta | None = None) -> str:
        """One turn of the quick model (routing, summaries, small talk), without tools."""
        ...


class OpenAIStreamingChat:
    def __init__(self, base_url: str, api_key: str, model: str, *, fast_model: str | None = None,
                 fast_extra_body: dict | None = None, thinking: bool = True, timeout_s: float = 60,
                 temperature: float = 0.1):
        self.name = model
        self._fast_model = fast_model or model
        self._fast_extra = fast_extra_body or None
        # The main model's thinking off: the same request fields as the quick calls.
        self._main_extra = None if thinking else self._fast_extra
        self._temperature = temperature
        self._client = openai.AsyncOpenAI(base_url=base_url, api_key=api_key, timeout=timeout_s, max_retries=1)

    async def stream(self, messages: list[dict], tools: list[dict] | None = None, *,
                     on_reasoning: Delta | None = None, on_content: Delta | None = None) -> Turn:
        kwargs: dict[str, Any] = {"tools": tools, "tool_choice": "auto"} if tools else {}
        response = await self._client.chat.completions.create(
            model=self.name, messages=messages, temperature=self._temperature, stream=True,
            stream_options={"include_usage": True}, extra_body=self._main_extra, **kwargs)
        turn = Turn()
        calls: dict[int, dict[str, str]] = {}
        async with response:
            async for chunk in response:
                if chunk.usage:
                    turn.usage = {"prompt": chunk.usage.prompt_tokens, "completion": chunk.usage.completion_tokens}
                if not chunk.choices:
                    continue
                delta = chunk.choices[0].delta
                reasoning = (delta.model_extra or {}).get("reasoning_content")
                if reasoning:
                    turn.reasoning += reasoning
                    if on_reasoning:
                        on_reasoning(reasoning)
                if delta.content:
                    turn.content += delta.content
                    if on_content:
                        on_content(delta.content)
                for call in delta.tool_calls or []:
                    # Tool calls arrive in pieces: the id and name first, then the arguments a few characters at a time.
                    entry = calls.setdefault(call.index, {"id": "", "name": "", "arguments": ""})
                    if call.id:
                        entry["id"] = call.id
                    if call.function and call.function.name:
                        entry["name"] += call.function.name
                    if call.function and call.function.arguments:
                        entry["arguments"] += call.function.arguments
        turn.tool_calls = [ToolCall(c["id"] or f"call_{i}", c["name"], c["arguments"]) for i, c in sorted(calls.items())]
        return turn

    async def quick(self, messages: list[dict], *, json_output: bool = False, timeout_s: float | None = None,
                    on_content: Delta | None = None) -> str:
        kwargs: dict[str, Any] = {"response_format": {"type": "json_object"}} if json_output else {}
        if timeout_s is not None:
            kwargs["timeout"] = timeout_s
        if on_content is None:
            response = await self._client.chat.completions.create(
                model=self._fast_model, messages=messages, temperature=0, extra_body=self._fast_extra, **kwargs)
            return response.choices[0].message.content or ""
        text = ""
        response = await self._client.chat.completions.create(
            model=self._fast_model, messages=messages, temperature=0.3, extra_body=self._fast_extra, stream=True,
            **kwargs)
        async with response:
            async for chunk in response:
                if chunk.choices and chunk.choices[0].delta.content:
                    text += chunk.choices[0].delta.content
                    on_content(chunk.choices[0].delta.content)
        return text


def parse_json_object(text: str) -> dict:
    """The JSON object in a model's answer; some models wrap it in a code fence even when asked not to."""
    text = text.strip()
    if text.startswith("```"):
        text = text.strip("`")
        text = text[text.find("{"):]
    start, end = text.find("{"), text.rfind("}")
    if start < 0 or end < start:
        raise ValueError("no JSON object in the answer")
    value = json.loads(text[start:end + 1])
    if not isinstance(value, dict):
        raise ValueError("not a JSON object")
    return value
