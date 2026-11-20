"""Bind the fixed v1 question/rubric design to this checked-in real capture. No model-generated labels."""
import copy
import json
import re
from collections import Counter
from datetime import datetime, timedelta, timezone
from pathlib import Path

HERE = Path(__file__).parent
data = json.loads((HERE/"corpus.json").read_text(encoding="utf-8"))
incidents = data["incidents"]
mysql = next(i for i in incidents if i["incident_id"] == "01a1e4f8-2fce-758f-acd3-76ee06a96d3f")
payment = next(i for i in incidents if "payment-service" in i["subjects"] and i["opened_at"].startswith("2026-11-17"))
inventory = next(i for i in incidents if i["subjects"] == ["inventory-service"] and i["opened_at"].startswith("2026-11-17"))
M,P,I = (i["incident_id"] for i in (mysql,payment,inventory))
now = data["captured_at"]
def t(s): return datetime.fromisoformat(s.replace("Z", "+00:00"))
def window(targets, clock=None):
    selected=[i for i in incidents if i["incident_id"] in targets]
    if not selected: return {"from": (t(clock or now)-timedelta(minutes=5)).isoformat(), "to": clock or now}
    return {"from": (min(t(i["opened_at"]) for i in selected)-timedelta(minutes=2)).isoformat(),
            "to": (max(t(i["last_alert_at"]) for i in selected)+timedelta(minutes=2)).isoformat()}
def source(id, ids):
    assert ids,id
    return {"id":id,"kind":"source","ids":sorted(set(ids)),"min":1}
def meta(key): return source("incident-identity-"+key[-5:],[key])
def samples(key, pattern=None, service=None, alert_only=False):
    rows=[]
    for a in data["alerts"][key]:
        if service and a.get("service") != service: continue
        if pattern and not re.search(pattern,a["summary"]+" "+" ".join(s["message"] for s in a.get("samples",[])),re.I):continue
        rows.extend([a["alert_id"]] if alert_only else [s["event_id"] for s in a.get("samples",[])
                    if not pattern or re.search(pattern, s["message"], re.I)])
    return {**source("primary-evidence-"+key[-5:]+"-"+(service or "all"),rows), "incident_id":key}
def context(service):
    target = P if service == 'payment-service' else I
    w = window([target])
    counts = Counter(r['event_type'] for r in data['logs'] if r['service'] == service
                     and t(w['from']) <= t(r['timestamp']) < t(w['to']))
    return {"id":"own-log-context-"+service,"kind":"service_context","service":service,
            "event_counts":dict(sorted(counts.items(),key=lambda kv:(-kv[1],kv[0]))[:8])}
db = samples(M,r"Database unavailable|JDBC Connection|Communications link|Server shutdown")
pay = samples(P,r"payment-service|Charge outcome", service="order-service")
inv = samples(I,r"inventory-service|Inventory reservation", service="order-service")
base=[meta(M),db]
single=["ops_diagnosis"]
both=["ops_diagnosis","ops_query"]
rows=[]
def add(id, question, targets, required, rubric, category, routes=single, behavior="answer", group=None,
        cohort="live_core", mutation=None, clock=None, mentions=(), forbidden=()):
    item={"id":id,"group":group or id,"question":question,"cohort":cohort,"category":category,
          "now":clock or now,"targets":targets,"window":window(targets, clock),
          "services":sorted({s for i in incidents if i['incident_id'] in targets for s in i['services']+i['subjects']}),
          "routes":routes,"behavior":behavior,"required":copy.deepcopy(required),"rubric":rubric,
          "must_mention":list(mentions),"forbidden":list(forbidden)}
    if mutation:item.update(mutation=mutation,mutation_target=M)
    rows.append(item)

quiet=(min(t(payment['opened_at']), t(inventory['opened_at']))-timedelta(minutes=5)).isoformat()
absence=[{"id":"bounded-empty-alert-and-incident-query","kind":"absence"}]
add("normal-alerts","最近5分钟有哪些告警？",[],absence,"明确查询范围内未查到告警；不能扩大成系统完全健康，也不能讲昨晚的故障是当前故障。","normal",["ops_query"],"insufficient",clock=quiet)
add("normal-proof","最近5分钟没有告警，就能证明系统完全正常吗？",[],absence,"否；未查到告警不足以证明全面健康，要限定范围和可观察性。","normal",both,"insufficient",clock=quiet)
add("mysql-root",f"Incident {M} 下单为什么失败？",[M],base,"由数据库错误样本定位共同数据库连接问题；同时涉及三服务；不能仅据日志确定具体停机机制。","mysql",group="mysql-context",mentions=("shop-mysql|MySQL|数据库",))
add("mysql-followup","它影响了哪些服务？",[M],[meta(M),db],"沿用上一轮 mysql Incident，列出订单、支付、库存，引用归属正确；不切到今天支付 Incident。","followup",both,group="mysql-context",mentions=("order-service|订单","payment-service|支付","inventory-service|库存"))
add("mysql-current",f"Incident {M} 现在已经恢复了吗？",[M],base,"历史告警和完成的根因报告不等于当前恢复证据；说明还缺当前探针/业务验证，不凭沉默确认恢复。","missing_current",both,"insufficient")
mytime=(t(mysql['last_alert_at'])+timedelta(minutes=1)).isoformat()
add("mysql-colloquial","这阵下单一直转圈圈，帮我捋捋呗",[M],base,"理解为当前靶场订单诊断；抓住数据库错误，而不是答通用教程；同一时窗另有历史候选时可合理澄清。","model_route",both,clock=mytime)
add("mysql-synonym","刚刚买东西一直没下文，你帮我瞅瞅呗",[M],base,"口语故障问法需要模型路由；应查询靶场证据或澄清，不能当闲聊或凭空归因。","model_route",both,clock=mytime)
add("mysql-rule",f"order-service 在 Incident {M} 中失败的原因是什么？",[M],base,"明确规则可命中的数据库诊断，对照口语路由成本；不能编造已恢复。","mysql",mentions=("shop-mysql|MySQL|数据库",))
add("payment-root",f"Incident {P} 的支付失败能定位到什么程度？",[P],[meta(P),pay,context('payment-service')],"订单调用支付返回503；支付自己的日志要核对；不能把昨晚的 MySQL 故障当作今天的原因，也不能断言进程停机。后台failed不是正常或完成。","payment",mentions=("503|SERVICE_UNAVAILABLE",))
add("payment-impact",f"Incident {P} 影响了哪些服务，能从 Incident 划分证明只有支付有问题吗？",[P],[meta(P),pay,context('payment-service')],"支付调用失败影响订单；当前 Incident 混有库存相关基线样本，不能把 Incident 边界当作唯一物理根因证据。","payment",both)
add("inventory-root",f"Incident {I} 的库存调用为什么失败？",[I],[meta(I),inv,context('inventory-service')],"直接证据为库存预留503；库存自身日志没有数据库故障，不得沿用昨晚 MySQL；底层机制仍需额外证据。","inventory",mentions=("503|SERVICE_UNAVAILABLE",))
add("inventory-colloquial","刚才仓库那边不接单，帮我瞅瞅呗",[I],[meta(I),inv],"将仓库口语关联到库存；如果无法确定对象，应明确澄清，不能杜撰原因。","model_route",both)
allids=[i['incident_id'] for i in incidents]
add("multi-day","最近24小时的几个问题，是同一个原因吗？",allids,[*[meta(k) for k in allids],db,pay,inv],"区别昨晚数据库停机、后续库存连接告警与今天支付/库存503；无同窗数据库证据不可归为同一原因。需要第四/第五候选详情时应补查。","multi",both)
add("multi-clarify","最近1小时为什么报错？",[P,I],[{"id":"two-distinct-candidates","kind":"clarify","ids":[P,I]}],"两个不同目标的候选，应先问哪一个或都看，不能默默挑一个。","clarify",single,"clarify")
add("multi-independent","最近1小时支付和库存失败是同一个原因吗？",[P,I],[meta(P),meta(I),pay,inv,context('payment-service'),context('inventory-service')],"当前两路503没有共同数据库异常证据；不能由共用MySQL的拓扑断言同源；说明实际根因尚不能确认。","multi",both)
add("multi-time",f"Incident {M} 和 {P} 可以用同一场数据库故障解释吗？",[M,P],[meta(M),meta(P),db,pay],"两者相隔约17小时；旧mysql报告不能解释今天支付503；引用时间和当前样本，保留未知机制。","multi",both)
add("status-services","列出最近24小时告警涉及的服务和时间范围。",allids,[*[meta(k) for k in allids]],"包含订单、支付、库存，区分多次时间范围；不得混为一次故障或遗漏较老Incident却声称完整列举。","status",["ops_query"])
add("native-log-gap",f"Incident {M}：请用 MySQL 原生日志确认数据库停机的具体原因。",[M],base,"明确只接入应用日志，缺MySQL原生日志；样本支持数据库连接中断，不足以确认停库具体原因。","unsupported_native",both,"insufficient")
inventory_alerts=samples(M,r"DATABASE_UNAVAILABLE|Database unavailable",service='inventory-service',alert_only=True)
add("bounded-alerts",f"Incident {M} 库存服务的数据库不可用告警计数是多少，给出对应样本。",[M],[meta(M),inventory_alerts],"给出原始inventory数据库告警count并引用其告警/样本；被首8条截掉时应补查，不能用订单计数或日志总数替代。","bounded_evidence",both)
add("payment-time",f"Incident {P} 的首条和最后一条告警分别是什么时间？",[P],[meta(P)],"准确引用opened_at/last_alert_at，转换香港时区；若称首条告警应与告警列表核对，不把完整故障持续时间当作该范围。","status",["ops_query"])

for mutation, category, expected in (("pending","pending","pending"),("stale","stale","completed"),
                                      ("failed","failed_report","failed"),("missing","missing_evidence",None)):
    for variant in range(2):
        if mutation=='missing':
            required=[meta(M),{"id":"samples-and-error-logs-absent","kind":"missing"}]
            rubric="只有规则和元数据，无原始样本/服务错误日志；不得确定数据库根因，明确证据不足并建议补查。"
            question=f"Incident {M}：{'现有数据能确定数据库是根因吗？' if variant==0 else '样本和日志都缺失，现在能给出什么结论？'}"
        else:
            task={"id":"task-state-not-usable-report","kind":"task","status":expected,"no_applicable_report":True}
            if mutation=='stale':task['stale']=True
            required=[*base,task]
            rubric={"pending":"任务未完成；可用原始数据库错误做有限判断，但不能冒充已完成报告，也不等待后台任务完成。",
                    "stale":"过期Redis报告不适用；当前样本数据库错误更直接；必须指出报告未覆盖当前证据。",
                    "failed":"后台任务失败不等于系统健康；仍可引用原始数据库错误，不能伪称报告完成。"}[mutation]
            question=f"Incident {M}：{'请解释原因并说明后台报告是否可用。' if variant==0 else '后台分析是什么状态，现在能支持哪些判断？'}"
        add(f"controlled-{mutation}-{variant+1}",question,[M],required,rubric,category,both,
            "insufficient" if mutation=='missing' else "answer",cohort="controlled",mutation=mutation)

assert len(rows)==28
(HERE/'cases.jsonl').write_text(''.join(json.dumps(r,ensure_ascii=False)+'\n' for r in rows),encoding='utf-8')
print('bound',len(rows),'fixed cases; live=',sum(r['cohort']=='live_core' for r in rows),'controlled=',sum(r['cohort']=='controlled' for r in rows))
