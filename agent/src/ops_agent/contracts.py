"""The Kafka messages shared with core-service. contracts/kafka/*.schema.json is the definition; the models here
are for typed access, and every message is also checked against its schema."""

import json
from datetime import datetime, timezone
from functools import cache
from pathlib import Path
from typing import Literal
from uuid import UUID

import jsonschema
from pydantic import BaseModel, Field

AGENT_TASKS_HIGH = "agent.tasks.high"
AGENT_RESULTS = "agent.results"
# Dead letters of both task topics (deploy/compose/kafka/create-topics.sh).
AGENT_TASKS_DLT = "agent.tasks.dlt"

INCIDENT_ANALYSIS = "incident_analysis"


class AgentTask(BaseModel):
    task_id: UUID
    kind: str
    incident_id: UUID
    requested_by: str
    requested_at: datetime
    deadline: datetime
    language: str = "zh"


class Evidence(BaseModel):
    """alert: an alert id; event: a sample event id; topology: a catalog entry; incident: the incident id."""
    kind: Literal["alert", "event", "topology", "incident"]
    id: str = Field(min_length=1)
    note: str | None = Field(default=None, max_length=1000)


class Hypothesis(BaseModel):
    component: str = Field(min_length=1)
    cause: str = Field(max_length=2000)
    confidence: Literal["high", "medium", "low"]
    evidence: list[Evidence] = Field(default_factory=list, max_length=20)


class AgentResult(BaseModel):
    task_id: UUID
    incident_id: UUID
    status: Literal["completed", "failed"]
    summary: str | None = Field(default=None, max_length=4000)
    uncertain: bool = False
    hypotheses: list[Hypothesis] = Field(default_factory=list, max_length=5)
    next_steps: list[str] = Field(default_factory=list, max_length=10)
    model: str | None = None
    tool_calls: int = 0
    duration_ms: int = 0
    error: str | None = None
    completed_at: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))

    def to_json(self) -> str:
        return self.model_dump_json(exclude_none=True)


class ContractError(ValueError):
    pass


class Schemas:
    def __init__(self, directory: Path):
        self._directory = directory

    def validate(self, name: str, message: dict) -> None:
        """Raises ContractError if the message does not follow contracts/kafka/<name>.schema.json."""
        errors = sorted(_validator(self._directory / f"{name}.schema.json").iter_errors(message), key=str)
        if errors:
            raise ContractError(f"{name}: " + "; ".join(f"{list(e.absolute_path)}: {e.message}" for e in errors[:5]))

    def task(self, raw: bytes | str) -> AgentTask:
        try:
            message = json.loads(raw)
        except ValueError as e:
            raise ContractError(f"agent-task: not JSON: {e}") from e
        self.validate("agent-task", message)
        return AgentTask.model_validate(message)

    def result(self, result: AgentResult) -> str:
        text = result.to_json()
        self.validate("agent-result", json.loads(text))
        return text


@cache
def _validator(path: Path) -> jsonschema.Draft202012Validator:
    schema = json.loads(path.read_text(encoding="utf-8"))
    return jsonschema.Draft202012Validator(schema, format_checker=jsonschema.Draft202012Validator.FORMAT_CHECKER)
