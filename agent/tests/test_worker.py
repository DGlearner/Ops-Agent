import json
import threading
from datetime import datetime, timedelta, timezone

from conftest import A1, INCIDENT_ID, ScriptedModel, mysql_answer
from ops_agent.analyst import Analyst
from ops_agent.contracts import AGENT_RESULTS, AGENT_TASKS_DLT
from ops_agent.worker import Worker


def task_message(deadline_in=timedelta(minutes=3), task_id="0199f000-0000-7000-8000-0000000000f1") -> bytes:
    now = datetime.now(timezone.utc)
    return json.dumps({"task_id": task_id, "kind": "incident_analysis", "incident_id": INCIDENT_ID,
                       "requested_by": "user", "requested_at": now.isoformat(),
                       "deadline": (now + deadline_in).isoformat(), "language": "zh"}).encode()


def test_answers_a_task(core, schemas):
    worker = Worker(Analyst(core, ScriptedModel(mysql_answer({"kind": "alert", "id": A1}))), schemas)

    task, result = worker.handle(task_message())

    assert json.loads(result)["status"] == "completed"
    assert json.loads(result)["task_id"] == str(task.task_id)


def test_a_task_past_its_deadline_is_skipped(core, schemas):
    assert Worker(Analyst(core, ScriptedModel()), schemas).handle(task_message(timedelta(seconds=-1))) is None


def test_a_failing_analysis_is_answered_as_failed(core, schemas, fake_core):
    fake_core.fail.add(f"/api/v1/incidents/{INCIDENT_ID}")

    _, result = Worker(Analyst(core, ScriptedModel()), schemas).handle(task_message())

    answer = json.loads(result)
    assert answer["status"] == "failed" and answer["model"] == "scripted"
    assert "503" in answer["error"]


class FakeMessage:
    def __init__(self, value, key=b"k"):
        self._value, self._key = value, key

    def value(self):
        return self._value

    def key(self):
        return self._key

    def error(self):
        return None


class FakeConsumer:
    def __init__(self, stop, *messages):
        self.stop, self.messages, self.committed, self.closed = stop, list(messages), [], False

    def subscribe(self, topics):
        self.topics = topics

    def poll(self, timeout):
        if not self.messages:
            self.stop.set()
            return None
        return self.messages.pop(0)

    def commit(self, message, asynchronous):
        self.committed.append(message)

    def close(self):
        self.closed = True


class FakeProducer:
    def __init__(self):
        self.sent = []

    def produce(self, topic, key, value, headers, on_delivery):
        self.sent.append((topic, key, value, headers))
        on_delivery(None, None)

    def flush(self, timeout):
        return 0


def test_answers_tasks_from_kafka_and_sets_aside_what_is_not_a_task(core, schemas):
    stop = threading.Event()
    good, bad = FakeMessage(task_message()), FakeMessage(b"not json")
    consumer, producer = FakeConsumer(stop, good, bad), FakeProducer()
    worker = Worker(Analyst(core, ScriptedModel(mysql_answer({"kind": "alert", "id": A1}))), schemas, consumer,
                    producer)

    worker.run(stop)

    assert [(topic, key) for topic, key, _, _ in producer.sent] == [
        (AGENT_RESULTS, b"0199f000-0000-7000-8000-0000000000f1"), (AGENT_TASKS_DLT, b"k")]
    assert "not JSON" in producer.sent[1][3]["error"]
    assert consumer.committed == [good, bad] and consumer.closed
