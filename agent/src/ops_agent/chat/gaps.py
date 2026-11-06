"""What is still missing before the next step (docs/agent-design.md §3.4, §13.4).

Worked out here from what was located, the case, the runbook and its probes, not left to the model: each gap says
which next steps it blocks. propose_action is checked against them (a remediation while the target is ambiguous, or
before its preconditions were seen, is turned down with the gap that blocks it), and the prompt lists them so the
model can say why it asks, looks further or proposes.
"""

from dataclasses import asdict, dataclass

from .runbooks import Match, Observation, options

REMEDIATE = "remediate"


@dataclass(frozen=True)
class Gap:
    kind: str
    detail: str
    blocks: tuple[str, ...] = ()
    # The operation this gap is about, when it is about one.
    op: str | None = None

    def as_dict(self) -> dict:
        return {k: v for k, v in asdict(self).items() if v not in (None, ())}


def compute(*, incidents: list[dict], scope_kind: str, components_pointed: list[set[str]], case: dict | None,
            matched: Match | None, observations: list[Observation], forbidden: dict[str, str],
            unverified: list[str]) -> list[Gap]:
    gaps: list[Gap] = []
    distinct = {frozenset(c) for c in components_pointed if c}
    if scope_kind == "window" and len(incidents) > 1 and len(distinct) > 1:
        gaps.append(Gap("target_ambiguous", "窗口里有几个 Incident 指向不同的组件，还不确定要处理哪一个",
                        (REMEDIATE,)))
    if not incidents:
        gaps.append(Gap("no_incident", "没有找到相关的 Incident", (REMEDIATE,)))
        return gaps
    if case is None:
        gaps.append(Gap("no_case", "没有处置单（没有开启写操作，或 core-service 不支持）", (REMEDIATE,)))
    if matched is None:
        gaps.append(Gap("outside_catalog", "没有匹配的 Runbook：只能诊断和建议，处置要人来做或升级", (REMEDIATE,)))
    else:
        for option in options(matched.runbook, observations):
            if not option["ready"]:
                gaps.append(Gap("precondition_unmet", f"{option['title']}：" + "；".join(option["unmet"]),
                                (REMEDIATE,), option["op"]))
            elif option["op"] in forbidden:
                gaps.append(Gap("constraint_conflict", f"{option['title']} 被用户的限制排除（{forbidden[option['op']]}）",
                                (REMEDIATE,), option["op"]))
    for statement in unverified:
        gaps.append(Gap("claim_unverified", f"用户说的「{statement}」还没有被查到的数据证实"))
    if case is not None and case.get("status") == "verifying":
        gaps.append(Gap("awaiting_effect", "上一个处置已执行，恢复验证还没有结论：不能说已经恢复"))
    return gaps


def blocking(gaps: list[Gap], op: str) -> Gap | None:
    """The first gap that keeps this remediation from being proposed."""
    return next((g for g in gaps if REMEDIATE in g.blocks and (g.op is None or g.op == op)), None)
