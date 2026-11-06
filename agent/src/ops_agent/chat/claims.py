"""Whether an answer says a change was made: a completed verb ("已经把 I1 改为…"), in a sentence that does not
propose, wait, suppose or deny. A pattern, so unusual wording can fool it; used where being wrong is cheap: to add a
plain line under an answer whose card is still waiting (graph.verify), and to score answers (trace_eval)."""

import re

DONE = re.compile(r"(已经?|成功)[^。！\n]{0,30}(改为|改成|标记为|标为|设为|更新为|确认了|关闭了|解决了|重新分析|派发)"
                  r"|(改为|改成|标记为|标为|设为)[^。！\n]{0,20}(了|成功)")
NOT_DONE = re.compile(r"提出|建议|等待|待你|待确认|确认后|卡片|如果|若|是否|需要你|请你|请在|可|没有|未|失败|拒绝|过期|不能|"
                      r"无法|之后|以后")


def claimed(text: str) -> str | None:
    """The first sentence that says a change was made, if any."""
    for sentence in re.split(r"[。！\n]", text):
        if DONE.search(sentence) and not NOT_DONE.search(sentence):
            return sentence.strip()[:80]
    return None
