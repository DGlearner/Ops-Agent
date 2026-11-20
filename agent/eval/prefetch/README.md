# 证据预取评测数据与复现

这是离线固定问题驱动的有限评测，不是持续压测。结论见 [`docs/prefetch-evaluation.md`](../../../docs/prefetch-evaluation.md)。

## 数据来源和边界

`corpus.json` 是 core-service 实际 API 的完整分页导出：5 个 Incident、33 条告警、2,604 条应用 ECS 日志，覆盖 order/payment/inventory 三个服务。没有 MySQL 原生、Redis 或 Kafka 专用日志。日志窗口为 22 小时；不能据此声称覆盖整个生产系统。

本次新采集仅运行一次 45 秒的独立故障演练：3 orders/s、支付 30% 503、库存 50% 503。演练和流量已停止。支付 Incident 同时关联到库存相关的基线告警，不能把 Incident 边界当作物理根因边界。两条后台分析因为本机 HTTP 代理干扰 localhost 请求而在模型调用前失败，保留该状态，不算作模型推理失败。

`cases.jsonl` 固定 28 问：20 问查真实 Java/PostgreSQL 服务；8 问以真实快照构造 pending、stale、failed、missing 的受控反事实。后者仍使用真实模型和 HTTP/SSE，但数据接口是 MockTransport 回放，必须单列，不能合并为真实端到端性能。

`build_dataset.py` 只绑定数据源 ID 和人工编写的要求，不生成模型答案。`dataset-manifest.json` 固定版本及内容哈希。每题保存时钟、对象、取证窗口、服务、允许路由、预期行为、必需证据组和语义 rubric。无告警题考虑接口上界 +1 分钟容忍；Incident 详情日志范围为 opened_at−2 分钟至 last_alert_at+2 分钟。

## 判定口径

- 初始证据齐备：首次答案模型调用前的 pack 满足所有必需证据组。按可接受的精确告警/样本 ID、目标 Incident、目标服务在固定窗口的事件计数，以及后台状态或无结果谓词核对；只有非空结果不算命中。正确澄清可在调用模型前完成，单列该行为。
- 答案正确：另存逐回答的证据/rubric 复核。自动路由、引用可解析和正则检查是 contracts，不能代替语义正确性。复核由 Codex 执行，不是盲评或独立人工双评。
- 零额外取证且正确：答案复核正确，模型没有提出额外只读取证工具请求，也没有第二轮答案模型调用（严格口径；原始 read_tool_calls 记录请求，可能含预算拒绝，实际执行另读 done.tool_calls/ledger）。路由模型调用单独计数；编排预取的 API 请求不会被误算为“零 API 请求”。
- 延迟：客户端发出真实 HTTP 请求至 SSE 首个非空 `answer.delta`，另记首事件、最后一次 interim 后保留答案首字及完整请求时长。澄清文本也属于用户可见答案，但单列后不能冒充根因诊断延迟。
- 失败全部保留在尝试分母；延迟只对有观测值的完成请求求分位数，另报缺失/失败。分位数采用 type 7：`h=(n−1)p`，相邻点线性插值。

20 个问题各重复 3 次的主评测每臂 60 个回答；它们共享 5 个 Incident，不是 60 个独立事故。P95 仅为该有限样本的描述值，不能推广为 SLA。8 个受控问题每臂 8 个回答，也不用于估计可靠尾延迟。

## 固定运行条件

两臂都是当前配置的 gpt-6-luna、temperature 0.1、最多 4 次工具调用、应用 thinking=false、fast_extra_body={}。供应商接口没有额外显式禁用 reasoning 的字段；原始 usage 中仍可能有 reasoning_tokens。不要表述为供应商层“完全关闭思考”。

每组每臂新建会话/Agent；mysql 追问复用该组会话。按 seed 打乱题组，交替 on/off 顺序；不禁用供应商缓存，记录缓存 token，不能当作严格冷缓存试验。为隔离取证，`casework_on=False`、writer=None，两臂均不执行处置单和操作写入。这与产品默认配置不同；prefetch=off 也跳过原有预取图节点，是整体取证编排的消融，不能把全部收益归因于单个并行请求。

## 复现命令

在 `agent/` 运行，需已有健康的真实 core-service、同一批数据和本地 `.env` 中的模型配置；不提交密钥、不重建环境：

```powershell
./.venv/Scripts/python.exe -m pytest tests/test_prefetch_eval.py -q --basetemp ../runs/prefetch-eval/test-new
./.venv/Scripts/python.exe -m ops_agent.chat.prefetch_eval run --cohort live_core --repeats 3 --out ../runs/prefetch-eval/live-new
./.venv/Scripts/python.exe -m ops_agent.chat.prefetch_eval run --cohort controlled --repeats 1 --out ../runs/prefetch-eval/controlled-new
./.venv/Scripts/python.exe -m ops_agent.chat.prefetch_eval summarize ../runs/prefetch-eval/live-new --review PATH_TO_REVIEWS
```

真实服务不能仅用快照重建：快照可以复现标签、回放和静态复核；重新运行真实端到端需要恢复相同数据，并确认哈希。评测进程只对本机 API 使用 trust_env=False；不会修改系统代理或产品代码。

原始记录包含每次 SSE 事件及客户端时间、最终答案、引用、首次 pack、模型消息/工具调用、core API 范围/大小/耗时、run ledger、错误和 usage。原始记录不能自动作为 gold labels。

8 次 pilot 与因正常时段标注错误而中止的 live-v1 均单独保留，不进入主统计；v1.1 在正式运行前固定问题/时钟；运行中发现跨 Incident 复用样本和整条告警匹配导致误收样本，v1.2 修正证据评分器并从两臂首次 pack 一致重算，原分数保留；语义 rubric 不变，因此这是开发评测，不能声称独立留出验证。不得筛掉正式批次中慢、错、超时或缺失的回答。
