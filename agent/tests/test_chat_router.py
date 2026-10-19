from datetime import datetime, timezone

import pytest

from chat_support import TOPOLOGY, ScriptedStreamingModel
from ops_agent.chat.router import Router, match_rules

NOW = datetime(2026, 10, 19, 15, 30, tzinfo=timezone.utc)
CATALOG = {n["name"] for n in TOPOLOGY["nodes"]}
FOCUS = {"incidents": [{"incident_id": "x", "ref": "I1", "title": "order-service answered with DATABASE_UNAVAILABLE"}]}
INCIDENT = "0199f000-0000-7000-8000-000000000011"


@pytest.mark.parametrize("question, route, services, minutes", [
    ("你好", "chitchat", (), None),
    ("你能做什么？", "chitchat", (), None),
    ("最近一小时有哪些告警", "ops_query", (), 60),
    ("现在有几个 Incident？", "ops_query", (), None),
    ("payment-service 现在状态怎么样", "ops_query", ("payment-service",), None),
    ("最近有哪些报错", "ops_query", (), None),
    ("刚才下单为什么一直失败？", "ops_diagnosis", ("order-service",), 30),
    ("支付服务过去 2 小时一直超时", "ops_diagnosis", ("payment-service",), 120),
    ("过去十五分钟库存服务有没有异常", "ops_query", ("inventory-service",), 15),
    (f"{INCIDENT} 是怎么回事", "ops_diagnosis", (), None),
    ("什么是缓存击穿", "ops_knowledge", ("shop-redis",), None),
    ("连接池满了一般怎么排查", "ops_knowledge", (), None),
    ("帮我写一首诗", "out_of_scope", (), None),
])
def test_rules_decide_when_the_words_leave_no_doubt(question, route, services, minutes):
    match = match_rules(question, NOW, CATALOG, has_focus=False)

    assert (match.route, match.sure) == (route, True), match.reason
    assert match.entities.services == services
    assert match.entities.minutes == minutes


def test_an_id_in_the_question_is_an_entity():
    match = match_rules(f"看看 {INCIDENT} 的状态", NOW, CATALOG, has_focus=False)

    assert match.route == "ops_query" and match.entities.incident_ids == (INCIDENT,)


def test_a_follow_up_about_the_cause_goes_to_the_incident_under_discussion():
    match = match_rules("接下来怎么处理？", NOW, CATALOG, has_focus=True)

    assert (match.route, match.sure, match.entities.follow_up) == ("ops_diagnosis", True, True)


def test_without_a_conversation_the_same_question_is_left_to_the_model():
    assert not match_rules("接下来怎么处理？", NOW, CATALOG, has_focus=False).sure


@pytest.mark.parametrize("question, guess", [
    ("那支付呢", "ops_query"), ("MySQL 和刚才比呢", "ops_query"), ("Redis 是做什么的", "ops_query"),
    ("今天辛苦了", "chitchat"), ("订单量正常吗", "ops_query"),
    # Databases or caches in general, or this system's? The model tells.
    ("数据库是不是挂了", "ops_diagnosis"), ("缓存穿透怎么解决比较好", "ops_diagnosis"),
    # Weak query words also start a diagnosis.
    ("支付一直转圈圈是什么情况", "ops_query"), ("下单接口 p99 飙到 2 秒了，查一下", "ops_query"),
])
def test_when_unsure_the_rules_only_guess(question, guess):
    match = match_rules(question, NOW, CATALOG, has_focus=False)

    assert (match.route, match.sure) == (guess, False)


async def test_the_model_decides_what_the_rules_cannot_and_sees_the_conversation():
    model = ScriptedStreamingModel(quick=['{"route": "ops_query", "reason": "追问支付服务的情况", '
                                          '"services": ["payment-service", "no-such"], "minutes": null, '
                                          '"follow_up": true}'])
    history = [{"role": "user", "content": "刚才下单为什么失败"}, {"role": "assistant", "content": "MySQL 不可用"}]

    decision = await Router(model).decide("那支付呢？", now=NOW, catalog=CATALOG, focus=FOCUS, history=history)

    assert (decision.route, decision.method) == ("ops_query", "model")
    assert decision.entities.services == ("payment-service",)
    assert decision.entities.follow_up
    prompt = model.quick_calls[0][1]["content"]
    assert "刚才下单为什么失败" in prompt and "DATABASE_UNAVAILABLE" in prompt and "那支付呢" in prompt


async def test_sure_rules_do_not_ask_the_model():
    model = ScriptedStreamingModel()

    decision = await Router(model).decide("最近一小时有哪些告警", now=NOW, catalog=CATALOG, focus={}, history=[])

    assert decision.method == "rule" and not model.quick_calls


@pytest.mark.parametrize("answer", [TimeoutError("slow"), "这是 ops_query", '{"route": "weather"}'])
async def test_when_the_model_fails_the_rules_guess_stands(answer):
    model = ScriptedStreamingModel(quick=[answer])

    decision = await Router(model).decide("那支付呢", now=NOW, catalog=CATALOG, focus={}, history=[])

    assert (decision.route, decision.method) == ("ops_query", "fallback")
    assert decision.entities.services == ("payment-service",)


async def test_without_a_model_the_rules_guess():
    decision = await Router(None).decide("那支付呢", now=NOW, catalog=CATALOG, focus={}, history=[])

    assert decision.method == "fallback" and "没有配置大模型" in decision.reason


def test_the_rules_are_never_wrong_when_sure_on_either_labeled_set():
    from ops_agent.chat import router_eval

    for path in router_eval.SETS:
        result = router_eval.rules_only(router_eval.load(path), NOW)
        assert result["precision_when_sure"] == 1.0, (path.name, result["wrong_when_sure"])
