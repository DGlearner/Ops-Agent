from datetime import datetime, timedelta, timezone
from uuid import UUID, uuid4

import httpx

from conftest import A1, A2, INCIDENT, FakeCore, alert
from ops_agent.baseline import BaselineAnalyst
from ops_agent.contracts import AgentTask
from ops_agent.core_api import CoreApi


def task(incident_id) -> AgentTask:
    now = datetime.now(timezone.utc)
    return AgentTask(task_id=uuid4(), kind="incident_analysis", incident_id=UUID(incident_id), requested_by="system",
                     requested_at=now, deadline=now + timedelta(minutes=3))


def api(fake: FakeCore) -> CoreApi:
    return CoreApi("http://core", client=httpx.Client(base_url="http://core",
                                                      transport=httpx.MockTransport(fake.handle)))


def test_suspects_what_the_failing_services_share(core, schemas):
    result = BaselineAnalyst(core).analyse(task(INCIDENT["incident_id"]))

    assert result.uncertain and result.model == "baseline"
    [hypothesis] = result.hypotheses
    assert hypothesis.component == "shop-mysql" and hypothesis.confidence == "low"
    assert [(e.kind, e.id) for e in hypothesis.evidence] == [("topology", "shop-mysql"), ("alert", A1),
                                                             ("alert", A2)]
    schemas.result(result)


def test_without_a_shared_dependency_suspects_what_the_alerts_point_at():
    incident = {**INCIDENT, "subjects": ["payment-service"], "shared_dependencies": []}
    calls = alert(A1, "shop-dependency-call-failures", "order-service", "2026-10-17T10:00:00Z", A2, "timeout",
                  dependency="payment-service")

    result = BaselineAnalyst(api(FakeCore(incident, [calls]))).analyse(task(incident["incident_id"]))

    [hypothesis] = result.hypotheses
    assert hypothesis.component == "payment-service"
    assert [e.id for e in hypothesis.evidence] == [A1]
