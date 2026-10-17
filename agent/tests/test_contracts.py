import json

import pytest

from ops_agent.contracts import AgentResult, ContractError

# As core-service writes it (Jackson, snake_case, Instant with microseconds).
TASK = {"task_id": "0199f000-0000-7000-8000-0000000000f1", "kind": "incident_analysis",
        "incident_id": "0199f000-0000-7000-8000-000000000001", "requested_by": "system",
        "requested_at": "2026-10-17T15:26:53.123456Z", "deadline": "2026-10-17T15:29:53.123456Z", "language": "zh"}


def test_reads_a_task_from_core_service(schemas):
    task = schemas.task(json.dumps(TASK).encode())
    assert str(task.incident_id) == TASK["incident_id"]
    assert task.deadline.tzinfo is not None


@pytest.mark.parametrize("change", [{"deadline": None}, {"kind": "restart_service"}, {"incident_id": "not-a-uuid"}])
def test_refuses_what_is_not_a_task(schemas, change):
    message = {k: v for k, v in {**TASK, **change}.items() if v is not None}
    with pytest.raises(ContractError):
        schemas.task(json.dumps(message))
    with pytest.raises(ContractError, match="not JSON"):
        schemas.task(b"{")


def test_a_failed_result_follows_the_schema(schemas):
    failed = AgentResult(task_id=TASK["task_id"], incident_id=TASK["incident_id"], status="failed", uncertain=True,
                         error="APIConnectionError: Connection error.")
    message = json.loads(schemas.result(failed))
    assert message["hypotheses"] == [] and message["completed_at"].endswith("Z")
    assert "summary" not in message
