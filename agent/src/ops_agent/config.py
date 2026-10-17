"""Settings, from environment variables prefixed OPS_AGENT_ or a .env file in the working directory."""

from pathlib import Path
from typing import Literal

from pydantic import SecretStr
from pydantic_settings import BaseSettings, SettingsConfigDict

# src/ops_agent/config.py -> the repository's contracts/kafka
_CONTRACTS = Path(__file__).resolve().parents[3] / "contracts" / "kafka"


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

    def require_llm(self) -> None:
        missing = [name for name, value in (("OPS_AGENT_LLM_BASE_URL", self.llm_base_url),
                                            ("OPS_AGENT_LLM_MODEL", self.llm_model),
                                            ("OPS_AGENT_LLM_API_KEY", self.llm_api_key)) if not value]
        if missing:
            raise SystemExit("mode llm needs " + ", ".join(missing) + " (or set OPS_AGENT_MODE=baseline)")
