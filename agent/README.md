# agent-runtime（Python）

Ops-Agent 的智能分析部分：从 Kafka 取 Incident 分析任务，让大模型通过 core-service 的**只读接口**查看这个 Incident，最后提交结构化的归因结论（根因组件、原因、证据、下一步建议）。它只读，不做任何操作。

```
core-service ──agent.tasks.high──▶ agent ──只读 HTTP──▶ core-service /api/v1/...
     ▲                               │
     └───────── agent.results ◀──────┘   core 校验证据后落库
```

- **谁来派发：** core-service 在 Incident 1 分钟没有新告警后自动派发（有新告警会再派发，每个 Incident 最多 3 次），也可以 `POST /api/v1/incidents/{id}/analyses` 手动触发。
- **工具：** 本 Incident 的告警（含样本日志）、时间线、服务拓扑、前后时间段的告警、同一时间的其他 Incident。全部映射到 core-service 已有的只读接口，见 `src/ops_agent/tools.py`。
- **结论的约束：** 每个假设都要引用证据，证据只能是本 Incident 真实存在的告警 id、样本事件 id、服务目录里的名字或 Incident id。Agent 提交前先自查一次，编造的 id 会退回让模型改正；core-service 落库前再校验，校验不过的证据单独存放、不作为支持。证据不足时模型要把 `uncertain` 设为 true。
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

```bash
.venv/bin/ops-agent run                          # 持续处理 Kafka 里的任务
.venv/bin/ops-agent analyse <incident_id>        # 立即分析一个 Incident 并打印结果，不经过 Kafka
.venv/bin/ops-agent --mode baseline run          # 不用大模型
```
