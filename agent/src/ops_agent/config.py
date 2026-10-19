"""Settings, from environment variables prefixed OPS_AGENT_ or a .env file in the working directory."""

from pathlib import Path
from typing import Any, Literal

from pydantic import SecretStr
from pydantic_settings import BaseSettings, SettingsConfigDict

# src/ops_agent/config.py -> the repository's contracts/kafka
_CONTRACTS = Path(__file__).resolve().parents[3] / "contracts" / "kafka"
# src/ops_agent/config.py -> agent/data, ignored by git
_DATA = Path(__file__).resolve().parents[2] / "data"


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="OPS_AGENT_", env_file=".env", extra="ignore")

    core_url: str = "http://127.0.0.1:8080"
    kafka_bootstrap: str = "localhost:9092"
    # The Compose stack publishes Kafka on 127.0.0.1 only, while "localhost" also resolves to ::1, which librdkafka
    # tries first and logs as a failure each time. any: both.
    kafka_address_family: Literal["any", "v4", "v6"] = "v4"
    group_id: str = "ops-agent"

    # llm: a language model calls the tools and draws the conclusion. baseline: no model, the incident's own
    # correlation hints are the answer; a yardstick, and a way to run everything without a model.
    mode: Literal["llm", "baseline"] = "llm"
    # Any OpenAI-compatible chat completions API with tool calling.
    llm_base_url: str | None = None
    llm_model: str | None = None
    llm_api_key: SecretStr | None = None
    llm_timeout_s: float = 60
    llm_temperature: float = 0.1

    # Read-only tool calls per analysis; then the model must conclude with what it has.
    max_tool_calls: int = 8
    contracts_dir: Path = _CONTRACTS

    # The chat server (ops-agent chat): docs/project-design.md §6.7.
    chat_host: str = "127.0.0.1"
    chat_port: int = 8083
    chat_db: Path = _DATA / "chat.sqlite3"
    # Tool calls the model may add to what the orchestration already fetched, per answer.
    chat_max_tool_calls: int = 4
    # Earlier turns the model sees word for word; older ones only through the session summary.
    chat_history_turns: int = 3
    # For the quick calls (routing, summaries): another model, and fields for the request body, e.g.
    # {"thinking": {"type": "disabled"}} to turn a reasoning model's thinking off. Defaults: the model above, nothing.
    chat_fast_model: str | None = None
    chat_fast_extra_body: dict[str, Any] = {}
    chat_router_timeout_s: float = 8
    # Experiment switches (§6.7): the model reasoning with its thinking on or off (off sends chat_fast_extra_body),
    # and the evidence fetched before the model is asked, or left for the model to fetch with its tools.
    chat_thinking: bool = True
    chat_prefetch: bool = True

    @property
    def llm_configured(self) -> bool:
        return bool(self.llm_base_url and self.llm_model and self.llm_api_key)

    def require_llm(self) -> None:
        missing = [name for name, value in (("OPS_AGENT_LLM_BASE_URL", self.llm_base_url),
                                            ("OPS_AGENT_LLM_MODEL", self.llm_model),
                                            ("OPS_AGENT_LLM_API_KEY", self.llm_api_key)) if not value]
        if missing:
            raise SystemExit("mode llm needs " + ", ".join(missing) + " (or set OPS_AGENT_MODE=baseline)")
