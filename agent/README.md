# agent-runtime（Python）

Ops-Agent 的智能部分，有两个入口，都只通过 core-service 的**只读接口**看数据；值班助手能提出的操作要用户确认后才执行（见下文）：

- **Incident 分析**（`ops-agent run`）：从 Kafka 取 Incident 分析任务，让大模型查看这个 Incident，提交结构化的归因结论（根因组件、原因、证据、下一步建议）。
- **值班助手**（`ops-agent chat`）：控制台对话页的后端，回答值班人员的问题，见下文“值班助手”。

```
core-service ──agent.tasks.high──▶ agent ──只读 HTTP──▶ core-service /api/v1/...
     ▲                               │
     └───────── agent.results ◀──────┘   core 校验证据后落库
```

- **谁来派发：** core-service 在 Incident 1 分钟没有新告警后自动派发（有新告警会再派发，每个 Incident 最多 3 次），也可以 `POST /api/v1/incidents/{id}/analyses` 手动触发。
- **工具：** 本 Incident 的告警（含样本日志）、时间线、服务拓扑、前后时间段的告警、同一时间的其他 Incident，以及某个服务自己的日志：检索（`search_logs`）和按事件类型统计（`count_logs`），默认看 Incident 前后 2 分钟。全部映射到 core-service 的只读接口，见 `src/ops_agent/tools.py`。
- **结论的约束：** 每个假设都要引用证据，证据只能是本 Incident 真实存在的告警 id、样本事件或查到的日志的 event id、服务目录里的名字或 Incident id。Agent 提交前先自查一次，编造的 id 会退回让模型改正；core-service 落库前再校验，校验不过的证据单独存放、不作为支持。证据不足时模型要把 `uncertain` 设为 true。
- **两种模式：** `llm` 用大模型（任何兼容 OpenAI Chat Completions、支持工具调用的接口）；`baseline` 不用大模型，直接拿 Incident 自带的关联提示（告警指向、共同依赖）当答案。基线用来衡量大模型归因的提升，也能在没配置大模型时跑通整条链路。
- **契约：** 消息格式以 `contracts/kafka/agent-task.schema.json`、`agent-result.schema.json` 为准，收发都按 schema 校验，和 Java 端共用。

## 运行

需要 Python 3.11+。

```bash
cd agent
python -m venv .venv
.venv/bin/pip install -e ".[dev]"      # Windows: .venv\Scripts\pip install -e ".[dev]"
.venv/bin/pytest -q
```

配置用环境变量，或放在 `agent/.env`（已被 git 忽略，参考 `.env.example`）：

| 变量 | 默认值 | 说明 |
|---|---|---|
| `OPS_AGENT_MODE` | `llm` | `llm` 或 `baseline` |
| `OPS_AGENT_LLM_BASE_URL` | — | 例如 `https://api.deepseek.com/v1` |
| `OPS_AGENT_LLM_MODEL` | — | 模型名 |
| `OPS_AGENT_LLM_API_KEY` | — | 只放在环境变量或 `.env` 里，不要提交 |
| `OPS_AGENT_CORE_URL` | `http://127.0.0.1:8080` | core-service |
| `OPS_AGENT_KAFKA_BOOTSTRAP` | `localhost:9092` | Kafka |
| `OPS_AGENT_MAX_TOOL_CALLS` | `8` | 每次分析最多调用几次工具，用完后必须下结论 |
| `OPS_AGENT_CHAT_PORT` | `8083` | 值班助手的端口（只监听 127.0.0.1） |
| `OPS_AGENT_CHAT_DB` | `agent/data/chat.sqlite3` | 会话和消息 |
| `OPS_AGENT_CHAT_MAX_TOOL_CALLS` | `4` | 编排取完证据后，模型最多再调用几次工具 |
| `OPS_AGENT_CHAT_HISTORY_TURNS` | `3` | 模型逐字看到的最近几轮，更早的进摘要 |
| `OPS_AGENT_CHAT_FAST_MODEL` | 同上面的模型 | 路由、摘要、闲聊用的快速模型 |
| `OPS_AGENT_CHAT_FAST_EXTRA_BODY` | — | 快速调用附加的请求字段，JSON。推理模型可以在这里关掉思考，DeepSeek 是 `{"thinking":{"type":"disabled"}}` |
| `OPS_AGENT_CHAT_THINKING` | `false` | 问题没说时，回答前模型是否深度思考（控制台的“深度思考”开关会说）；关闭时也用上面的字段 |
| `OPS_AGENT_CHAT_PREFETCH` | `true` | 编排先取证据再问模型；`false` 时模型自己用工具取（实验对照） |
| `OPS_AGENT_CHAT_ACTIONS` | `true` | 用户确认的操作卡片是否真的执行（调用 core-service 的写接口）；`false` 时照样提出，确认后记为没有执行 |
| `OPS_AGENT_CHAT_ACTION_TTL_S` | `900` | 卡片多久不处理就过期 |
| `OPS_AGENT_CHAT_CASEWORK` | `true` | 处置单、Runbook、探针和处置操作（`docs/agent-design.md` §13.4）；`false` 时和之前一样只问答 |
| `OPS_AGENT_VERIFY_POLL_S` | `5` | `ops-agent verify` 多久问一次有没有到期的恢复验证 |

```bash
.venv/bin/ops-agent run                          # 持续处理 Kafka 里的任务
.venv/bin/ops-agent analyse <incident_id>        # 立即分析一个 Incident 并打印结果，不经过 Kafka
.venv/bin/ops-agent --mode baseline run          # 不用大模型
.venv/bin/ops-agent chat                         # 值班助手，http://127.0.0.1:8083/api/v1/chat/docs
.venv/bin/ops-agent eval-router                  # 路由评测（agent/eval/ 的标注问题）
.venv/bin/ops-agent eval-trace --runs 3 --out ../runs/trace.json   # 轨迹评测：工具、对象、追问、操作与回执、记忆
.venv/bin/ops-agent eval-chat "刚才下单为什么失败？" --expect shop-mysql   # 在真实系统上计时，先在演练控制台注入故障
.venv/bin/ops-agent verify                       # 恢复验证：领取到期的验证任务，按告警和日志判断，带令牌提交
.venv/bin/ops-agent eval-outcome mysql-down hot-row-lock payment-errors --rounds 3 --out ../runs/outcome.json
                                                 # 结果评测：在靶场注入故障，脚本化值班人员确认处置，等验证结论
                                                 # 一个场景约 5 分钟（清场 + 验证窗口）；同一场景各轮结果可能不同，所以要多跑几轮
```

## 值班助手

设计见 `docs/project-design.md` §6.7；处置单、Runbook、处置操作和恢复验证见 `docs/agent-design.md`。

```
ops_agent/chat/
  server.py      FastAPI：会话接口，回答以 SSE 推送（route、step、thinking、answer、interim、action、memory、
                 questionnaire、citations、done）；确认或拒绝操作、回答问卷、审核经验
  agent.py       一次回答：读记忆、运行编排图、转发事件、把回答连同过程存下；断开即取消；从暂停点接着回答
  graph.py       LangGraph 编排：route → locate →（暂停：问卷）gather → reason → verify，或 smalltalk、knowledge
  actions.py     操作卡片：提出时校验，确认时 CAS 只执行一次，执行前再核对，回执按 core-service 的答复和读回的状态
  clarify.py     追问：什么时候先问、问卷的格式、回答的校验、暂停点
  claims.py      回答是否说“已经做了”：卡片还在等确认时加一行说明，轨迹评测也用它
  knowledge.py   跨会话的运维经验：候选、人工审核、按组件挑选
  router.py      两级路由：规则有把握时直接决定（0 ms），否则问快速模型；模型失败用规则的猜测
  tools.py       只读查询：编排直接调用来预取证据（含相关服务的日志统计和最新的警告、错误），其中 6 个也作为模型的工具
  evidence.py    证据编号（I1、A3、E2）：会话内不变，回答里的引用逐个核对
  memory.py      SQLite：会话、消息；三层记忆（当前话题、最近几轮、摘要，摘要按 CAS 写入）；操作、暂停点、经验；
                 运行记录（request_id 幂等、结束状态、上下文快照及其摘要）
  casework.py    处置单（存在 core-service）：编排写探针的事实和问卷的决定；模型的 update_case 只能写假设、
                 用户的情况和限制（逐字引用原话）
  runbooks.py    Runbook（ops_agent/runbooks/*.yaml）：匹配、探针、处置选项和前置条件
  gaps.py        缺口：对象不明确、前置条件没看到、被用户的限制排除……每个缺口阻塞哪些下一步
  usage.py       每轮的用量和耗时；GET /api/v1/chat/runs/{run_id} 串起运行、卡片、处置和验证（页面 /runs/:id）
  outcome_eval.py 结果评测（eval-outcome）
  prompts.py     提示词
  router_eval.py / latency_eval.py / trace_eval.py（+ eval_core.py 场景数据）   评测
```

- **没有大模型时**（`--mode baseline`）：路由只用规则，运维问题按查到的数据写出规则的结论和原始告警，知识类问题说明需要大模型。
- **测试：** `tests/test_chat_*.py` 用假的 core-service 和按脚本回答的模型，覆盖路由、编排的每个分支、工具次数用完、模型失败降级、引用核对、追问、摘要、取消、SSE 接口和 409；操作卡片（重复确认、版本不对、提出后被改、core-service 拒绝、200 但读回不一致、超时后读回、过期、拒绝、重启）、问卷与暂停点、经验的审核与隔离、摘要的并发写入，以及轨迹评测器本身。

