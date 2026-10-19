"""Which way a question goes. Two levels: rules first, which are instant and only decide when they are sure; the
quick model for the rest, which also sees the last turns, so that follow-ups like "那它影响了哪些服务" land
where the conversation is. If the model fails, the rules' best guess stands.

Routes:
- ops_diagnosis: why something in the watched system is failing, what it affects, what to do about it.
- ops_query: what is going on: alerts, incidents, services, without asking for a cause.
- ops_knowledge: operations in general (what is a cache stampede, how to look into a full pool), not this system.
- chitchat: greetings, thanks, what the assistant can do.
- out_of_scope: anything else.
"""

import json
import logging
import re
import time
from dataclasses import dataclass, field, replace
from datetime import datetime
from typing import Literal

from pydantic import BaseModel, Field, ValidationError

from . import prompts
from .llm import StreamingModel, parse_json_object

log = logging.getLogger(__name__)

Route = Literal["ops_diagnosis", "ops_query", "ops_knowledge", "chitchat", "out_of_scope"]
ROUTES: tuple[Route, ...] = ("ops_diagnosis", "ops_query", "ops_knowledge", "chitchat", "out_of_scope")
ROUTE_LABEL = {"ops_diagnosis": "故障诊断", "ops_query": "状态查询", "ops_knowledge": "运维知识",
               "chitchat": "闲聊", "out_of_scope": "超出范围"}
OPS_ROUTES = ("ops_diagnosis", "ops_query")

# What people call the catalog's components. Names the catalog itself uses are matched as they are.
SERVICE_ALIASES = {
    "order-service": ("订单服务", "订单", "下单", "order"),
    "inventory-service": ("库存服务", "库存", "inventory"),
    "payment-service": ("支付服务", "支付", "付款", "payment"),
    "shop-mysql": ("mysql", "数据库"),
    "shop-redis": ("redis", "缓存"),
}
UUID = re.compile(r"\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b")
WINDOW = re.compile(r"(\d+|[一二两三四五六七八九十]+)\s*(分钟|min|小时|个小时|h|天)")
NUMERALS = {"一": 1, "二": 2, "两": 2, "三": 3, "四": 4, "五": 5, "六": 6, "七": 7, "八": 8, "九": 9}

CHITCHAT = re.compile(r"^(你好|您好|嗨|hi|hello|hey|在吗|在不在|谢谢|多谢|感谢|谢了|thanks|thank you|好的|好|ok|嗯|"
                      r"你是谁|你叫什么|你能做什么|你可以做什么|你会什么|你会做什么|你有什么功能|怎么用|如何使用|帮助|help)"
                      r"[\s!！。.~？?呀啊呢吧]*$")
# Asking for a cause, an impact or a remedy.
WHY = ("为什么", "为啥", "原因", "根因", "怎么回事", "咋回事", "怎么了", "什么问题", "出问题", "导致", "引起", "影响",
       "怎么处理", "如何处理", "怎么办", "怎么解决", "如何解决", "排查", "修复", "why", "root cause", "what happened")
# Describing something failing: a diagnosis, unless the question asks for a list ("有哪些报错").
SYMPTOM = ("挂了", "宕机", "不可用", "失败", "报错", "出错", "异常", "超时", "变慢", "很慢", "卡住", "failing")
# Asking what is going on. The weak ones also start a diagnosis ("支付转圈圈是什么情况", "p99 飙到 2 秒，查一下").
QUERY = ("有哪些", "有没有", "多少", "几个", "几条", "列出", "列一下", "状态", "正常吗", "健康", "最新", "汇总", "统计",
         "拓扑", "依赖", "list", "how many", "status")
WEAK_QUERY = ("有什么", "看看", "看一下", "查一下", "查查", "情况", "show")
KNOWLEDGE = ("什么是", "是什么意思", "什么叫", "原理", "区别", "一般怎么", "一般如何", "通常怎么", "通常如何", "如何设计",
             "怎么设计", "最佳实践", "概念", "怎么避免", "如何避免", "怎么预防", "如何预防", "what is", "difference")
# Nouns of this system, and nouns that may as well be about operations in general ("延迟双删有什么问题").
SYSTEM_NOUNS = ("告警", "incident", "故障", "alert")
GENERIC_NOUNS = ("事件", "服务", "接口", "延迟", "错误", "日志", "系统", "线上", "秒杀", "抢购", "service", "error")
# Chinese names of the shop's services: as specific as the catalog's own names.
NAMED = ("订单服务", "库存服务", "支付服务")
# Words that put a question in the here and now of this system, rather than in general.
PRESENT = ("现在", "目前", "当前", "刚才", "刚刚", "最近", "今天", "昨天", "上午", "下午", "晚上", "这次", "这个", "那个",
           "它", "上面", "我们", "线上", "系统里")
FOLLOW_UP = ("它", "这个", "那个", "这次", "上面", "刚才那个", "该")
OUT_OF_SCOPE = ("写诗", "写一首", "笑话", "天气", "股票", "菜谱", "小说", "作文", "星座")


@dataclass(frozen=True)
class Entities:
    services: tuple[str, ...] = ()
    # How far back from now the question looks, if it says.
    minutes: int | None = None
    incident_ids: tuple[str, ...] = ()
    # Refers to what the conversation is already about (它、这个故障…).
    follow_up: bool = False

    def as_dict(self) -> dict:
        return {"services": list(self.services), "minutes": self.minutes, "incident_ids": list(self.incident_ids),
                "follow_up": self.follow_up}


@dataclass(frozen=True)
class Decision:
    route: Route
    method: Literal["rule", "model", "fallback"]
    reason: str
    entities: Entities = field(default_factory=Entities)
    duration_ms: int = 0

    @property
    def label(self) -> str:
        return ROUTE_LABEL[self.route]

    def as_dict(self) -> dict:
        return {"route": self.route, "label": self.label, "method": self.method, "reason": self.reason,
                "entities": self.entities.as_dict(), "duration_ms": self.duration_ms}


@dataclass(frozen=True)
class RuleMatch:
    route: Route
    sure: bool
    reason: str
    entities: Entities


def entities(question: str, now: datetime, catalog: set[str], has_focus: bool) -> Entities:
    q = question.lower()
    services = [name for name in sorted(catalog) if name in q]
    for name, aliases in SERVICE_ALIASES.items():
        if name not in services and name in catalog and any(_has(q, a) for a in aliases):
            services.append(name)
    return Entities(services=tuple(services), minutes=_minutes(q, now), incident_ids=tuple(UUID.findall(q)),
                    follow_up=has_focus and any(w in q for w in FOLLOW_UP))


def match_rules(question: str, now: datetime, catalog: set[str], has_focus: bool) -> RuleMatch:
    """The rules' answer: sure only when the words leave no doubt; otherwise a guess the model may overrule."""
    q = question.strip().lower()
    found = entities(q, now, catalog, has_focus)
    named = any(name in q for name in catalog) or _any(q, NAMED)
    why = _any(q, WHY)
    symptom = _any(q, SYMPTOM)
    query = _any(q, QUERY)
    weak_query = _any(q, WEAK_QUERY)
    knowledge = _any(q, KNOWLEDGE)
    present = _any(q, PRESENT) or found.minutes is not None
    generic = bool(found.services or _any(q, GENERIC_NOUNS))
    # Surely about this system: it names a component, an incident or a system noun, or speaks of now. "MySQL" or
    # "缓存" alone may be about databases or caches in general ("缓存穿透怎么解决比较好"): only a guess.
    system = bool(found.incident_ids or named or found.follow_up or _any(q, SYSTEM_NOUNS)
                  or (present and (generic or symptom)))
    scope = system or generic
    diagnosis = why or (symptom if not query else None)

    if CHITCHAT.match(q) and not scope:
        return RuleMatch("chitchat", True, "问候、致谢或询问能做什么", found)
    if found.incident_ids:
        if query and not diagnosis:
            return RuleMatch("ops_query", True, "问题里有 Incident 编号，问的是情况", found)
        return RuleMatch("ops_diagnosis", True, "问题里有 Incident 编号", found)
    if knowledge and not present and not named:
        return RuleMatch("ops_knowledge", True, f"问的是一般概念（“{knowledge}”），没有指向当前系统", found)
    if system and diagnosis and not knowledge:
        return RuleMatch("ops_diagnosis", True, f"问到系统里的{_what(found)}，并且问原因或故障（“{diagnosis}”）", found)
    if system and query and not diagnosis and not knowledge and not weak_query:
        return RuleMatch("ops_query", True, f"问到系统里的{_what(found)}，问的是情况（“{query}”）", found)
    if has_focus and why and not knowledge:
        return RuleMatch("ops_diagnosis", True, f"接着上文问原因或处理（“{why}”）", replace(found, follow_up=True))
    if not scope and _any(q, OUT_OF_SCOPE):
        return RuleMatch("out_of_scope", True, "与运维无关", found)
    # Not sure: the model decides, and if it cannot, this guess stands.
    if scope:
        return RuleMatch("ops_diagnosis" if diagnosis else "ops_query", False, "可能涉及系统，但问法不确定", found)
    if knowledge:
        return RuleMatch("ops_knowledge", False, "像是一般问题", found)
    return RuleMatch("chitchat", False, "没有运维相关的词", found)


class ModelRoute(BaseModel):
    route: Route
    reason: str = Field(default="", max_length=200)
    services: list[str] = Field(default_factory=list, max_length=10)
    minutes: int | None = Field(default=None, ge=1, le=7 * 24 * 60)
    follow_up: bool = False


class Router:
    def __init__(self, model: StreamingModel | None, timeout_s: float = 8):
        self._model = model
        self._timeout_s = timeout_s

    async def decide(self, question: str, *, now: datetime, catalog: set[str], focus: dict,
                     history: list[dict]) -> Decision:
        started = time.monotonic()
        has_focus = bool(focus.get("incidents"))
        rules = match_rules(question, now, catalog, has_focus)
        if rules.sure or self._model is None:
            method = "rule" if rules.sure else "fallback"
            reason = rules.reason if rules.sure else rules.reason + "（没有配置大模型，按规则猜测）"
            return Decision(rules.route, method, reason, rules.entities, _ms(started))
        try:
            routed = await self.classify(question, now=now, catalog=catalog, focus=focus, history=history)
        except (ValueError, ValidationError, json.JSONDecodeError) as e:
            log.warning("router: the model's answer is unusable (%s); the rules' guess stands", e)
            return Decision(rules.route, "fallback", rules.reason + "（模型的判断不可用，按规则猜测）", rules.entities,
                            _ms(started))
        except Exception as e:  # noqa: BLE001 - an unreachable model must not cost the user the answer
            log.warning("router: the model failed (%s: %s); the rules' guess stands", type(e).__name__, e)
            return Decision(rules.route, "fallback", rules.reason + "（模型不可用，按规则猜测）", rules.entities,
                            _ms(started))
        found = rules.entities
        services = tuple(dict.fromkeys([*found.services, *(s for s in routed.services if s in catalog)]))
        merged = Entities(services=services, minutes=found.minutes or routed.minutes,
                          incident_ids=found.incident_ids, follow_up=has_focus and (found.follow_up or routed.follow_up))
        return Decision(routed.route, "model", routed.reason or "模型判断", merged, _ms(started))


    async def classify(self, question: str, *, now: datetime, catalog: set[str], focus: dict,
                       history: list[dict]) -> ModelRoute:
        """The model's route alone; raises when there is no usable answer."""
        if self._model is None:
            raise ValueError("no model")
        text = await self._model.quick(prompts.router_messages(question, now, catalog, focus, history),
                                       json_output=True, timeout_s=self._timeout_s)
        return ModelRoute.model_validate(parse_json_object(text))


def _minutes(q: str, now: datetime) -> int | None:
    if m := WINDOW.search(q):
        n = _number(m.group(1))
        unit = m.group(2)
        minutes = n if unit in ("分钟", "min") else n * 60 if unit in ("小时", "个小时", "h") else n * 24 * 60
        return max(1, min(minutes, 7 * 24 * 60))
    if "半小时" in q:
        return 30
    if "刚才" in q or "刚刚" in q:
        return 30
    if "今天" in q:
        return max(1, int((now - now.replace(hour=0, minute=0, second=0, microsecond=0)).total_seconds() // 60))
    if "昨天" in q:
        return 48 * 60
    return None


def _number(text: str) -> int:
    """12, 一, 十, 十五, 二十, 三十。"""
    if text.isdigit():
        return int(text)
    if "十" in text:
        tens, _, ones = text.partition("十")
        return NUMERALS.get(tens, 1) * 10 + NUMERALS.get(ones, 0)
    return NUMERALS.get(text, 1)


def _has(q: str, alias: str) -> bool:
    if alias.isascii():
        return re.search(rf"(?<![a-z0-9-]){re.escape(alias)}(?![a-z0-9-])", q) is not None
    return alias in q


def _any(q: str, words: tuple[str, ...]) -> str | None:
    return next((w for w in words if w in q), None)


def _what(found: Entities) -> str:
    if found.incident_ids:
        return " Incident"
    if found.services:
        return " " + "、".join(found.services)
    if found.follow_up:
        return "上文提到的 Incident"
    return "告警或故障"


def _ms(started: float) -> int:
    return int((time.monotonic() - started) * 1000)
