"""The language model behind the analysis: any OpenAI-compatible chat completions API with tool calling."""

import logging
from dataclasses import dataclass, field
from typing import Any, Protocol

import openai

log = logging.getLogger(__name__)


@dataclass(frozen=True)
class ToolCall:
    id: str
    name: str
    arguments: str


@dataclass(frozen=True)
class Reply:
    content: str | None
    tool_calls: list[ToolCall] = field(default_factory=list)
    # Provider-specific fields of the assistant message that must be sent back with it (e.g. reasoning_content).
    extra: dict[str, Any] = field(default_factory=dict)

    def as_message(self) -> dict:
        message: dict[str, Any] = {"role": "assistant", "content": self.content or "", **self.extra}
        if self.tool_calls:
            message["tool_calls"] = [{"id": c.id, "type": "function",
                                      "function": {"name": c.name, "arguments": c.arguments}}
                                     for c in self.tool_calls]
        return message


class ChatModel(Protocol):
    name: str

    def complete(self, messages: list[dict], tools: list[dict], force_tool: str | None = None) -> Reply:
        """One model turn. With force_tool the model is told to call that tool."""
        ...


class OpenAIChat:
    def __init__(self, base_url: str, api_key: str, model: str, timeout_s: float = 60, temperature: float = 0.1):
        self.name = model
        self._temperature = temperature
        self._client = openai.OpenAI(base_url=base_url, api_key=api_key, timeout=timeout_s, max_retries=2)

    def complete(self, messages: list[dict], tools: list[dict], force_tool: str | None = None) -> Reply:
        choice: Any = {"type": "function", "function": {"name": force_tool}} if force_tool else "auto"
        try:
            response = self._create(messages, tools, choice)
        except openai.BadRequestError:
            if not force_tool:
                raise
            # Not every compatible API accepts a named tool_choice; offering only that tool does nearly as well.
            log.info("named tool_choice refused, retrying with auto")
            response = self._create(messages, tools, "auto")
        message = response.choices[0].message
        extra = {}
        reasoning = (message.model_extra or {}).get("reasoning_content")
        if reasoning:
            extra["reasoning_content"] = reasoning
        return Reply(message.content,
                     [ToolCall(c.id, c.function.name, c.function.arguments or "") for c in message.tool_calls or []],
                     extra)

    def _create(self, messages: list[dict], tools: list[dict], choice: Any):
        return self._client.chat.completions.create(model=self.name, messages=messages, tools=tools,
                                                    tool_choice=choice, temperature=self._temperature)
