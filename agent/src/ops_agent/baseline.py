"""An analysis without a language model: the incident's own correlation hints are taken as the answer. A yardstick
for the agent's attribution in the lab, and a way to run the whole pipeline before a model is configured."""

import time
from datetime import datetime

from .contracts import AgentResult, AgentTask, Evidence, Hypothesis
from .core_api import CoreApi


class BaselineAnalyst:
    name = "baseline"

    def __init__(self, core: CoreApi):
        self._core = core

    def analyse(self, task: AgentTask, deadline: datetime | None = None) -> AgentResult:
        started = time.monotonic()
        incident = self._core.incident(task.incident_id)
        alerts = sorted(self._core.incident_alerts(task.incident_id), key=lambda a: a["created_at"])
        subjects = incident.get("subjects") or []
        shared = incident.get("shared_dependencies") or []
        if shared:
            # Several subjects failing together, all depending on the same thing: suspect that thing.
            evidence = [_alert(a) for a in alerts[:5]]
            hypotheses = [Hypothesis(component=d, cause=f"{'、'.join(subjects)} 同时出现异常，它们都依赖 {d}",
                                     confidence="low", evidence=[Evidence(kind="topology", id=d), *evidence])
                          for d in shared[:3]]
        else:
            hypotheses = [Hypothesis(component=s, cause=f"告警指向 {s}", confidence="low",
                                     evidence=[_alert(a) for a in alerts if _subject(a) == s][:5])
                          for s in subjects[:3]]
        components = [h.component for h in hypotheses]
        return AgentResult(
            task_id=task.task_id, incident_id=task.incident_id, status="completed",
            summary=f"基线归因（没有使用大模型，只看告警指向和共同依赖）：{incident['title']}。"
                    f"可能的问题在 {'、'.join(components) or '未知组件'}。",
            uncertain=True, hypotheses=hypotheses,
            next_steps=[f"检查 {c} 的状态、日志和最近的变更" for c in components],
            model=self.name, tool_calls=0, duration_ms=int((time.monotonic() - started) * 1000))


def _subject(alert: dict) -> str | None:
    """As core-service's correlation: the callee of a failing call, else the service that alerted."""
    return (alert.get("group_values") or {}).get("fields.dependency") or alert.get("service")


def _alert(alert: dict) -> Evidence:
    return Evidence(kind="alert", id=alert["alert_id"], note=alert["summary"][:200])
