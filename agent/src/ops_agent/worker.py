"""Takes analysis tasks from Kafka one at a time and writes the answers back. A task's offset is committed only after
its answer was delivered, so a crash means the task is analysed again rather than lost; core-service stores one
answer per task."""

import logging
import threading
import time
from datetime import datetime, timezone
from typing import Protocol

from .contracts import (AGENT_RESULTS, AGENT_TASKS_DLT, AGENT_TASKS_HIGH, AgentResult, AgentTask, ContractError,
                        Schemas)

log = logging.getLogger(__name__)


class Analyst(Protocol):
    name: str

    def analyse(self, task: AgentTask, deadline: datetime | None = None) -> AgentResult:
        ...


class Worker:
    def __init__(self, analyst: Analyst, schemas: Schemas, consumer=None, producer=None):
        self._analyst = analyst
        self._schemas = schemas
        self._consumer = consumer
        self._producer = producer

    @classmethod
    def connect(cls, analyst: Analyst, schemas: Schemas, bootstrap: str, group_id: str,
                address_family: str = "any") -> "Worker":
        from confluent_kafka import Consumer, Producer
        common = {"bootstrap.servers": bootstrap, "broker.address.family": address_family}
        consumer = Consumer({**common, "group.id": group_id, "enable.auto.commit": False,
                             "auto.offset.reset": "earliest",
                             # An analysis takes up to the task's deadline (minutes) between two polls.
                             "max.poll.interval.ms": 900_000})
        producer = Producer({**common, "enable.idempotence": True, "acks": "all"})
        return cls(analyst, schemas, consumer, producer)

    def handle(self, raw: bytes | str) -> tuple[AgentTask, str] | None:
        """The answer to one task message as JSON, or None for a task past its deadline. ContractError if the
        message is not a task."""
        task = self._schemas.task(raw)
        if task.deadline <= datetime.now(timezone.utc):
            log.info("task %s for incident %s is past its deadline, skipped", task.task_id, task.incident_id)
            return None
        log.info("task %s: analysing incident %s (requested by %s)", task.task_id, task.incident_id,
                 task.requested_by)
        started = time.monotonic()
        try:
            result = self._analyst.analyse(task, deadline=task.deadline)
            return task, self._schemas.result(result)
        except Exception as e:  # noqa: BLE001 - every failure becomes a failed answer core-service can show
            log.warning("task %s failed: %s", task.task_id, e, exc_info=not isinstance(e, ContractError))
            failed = AgentResult(task_id=task.task_id, incident_id=task.incident_id, status="failed", uncertain=True,
                                 model=self._analyst.name, tool_calls=getattr(e, "tool_calls", 0),
                                 duration_ms=int((time.monotonic() - started) * 1000),
                                 error=f"{type(e).__name__}: {e}"[:1000])
            return task, self._schemas.result(failed)

    def run(self, stop: threading.Event) -> None:
        self._consumer.subscribe([AGENT_TASKS_HIGH])
        log.info("waiting for tasks on %s (analyst: %s)", AGENT_TASKS_HIGH, self._analyst.name)
        try:
            while not stop.is_set():
                message = self._consumer.poll(1.0)
                if message is None:
                    continue
                if message.error():
                    log.warning("consumer: %s", message.error())
                    continue
                try:
                    answer = self.handle(message.value())
                except ContractError as e:
                    log.warning("not a task, sent to %s: %s", AGENT_TASKS_DLT, e)
                    self._send(AGENT_TASKS_DLT, message.key(), message.value(), {"error": str(e)[:500]})
                else:
                    if answer is not None:
                        task, result = answer
                        self._send(AGENT_RESULTS, str(task.task_id).encode(), result.encode())
                        log.info("task %s answered", task.task_id)
                self._consumer.commit(message=message, asynchronous=False)
        finally:
            self._consumer.close()

    def _send(self, topic: str, key: bytes | None, value: bytes | None, headers: dict | None = None) -> None:
        errors = []
        self._producer.produce(topic, key=key, value=value, headers=headers,
                               on_delivery=lambda err, msg: errors.append(err) if err else None)
        if self._producer.flush(30) > 0 or errors:
            # Not committing the task: it is analysed again after a restart.
            raise RuntimeError(f"cannot deliver to {topic}: {errors or 'timed out'}")
