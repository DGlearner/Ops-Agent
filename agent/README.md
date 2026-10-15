# agent-runtime（待定）

Agent 部分（Python）的功能与实现方式尚未确定。

Java 侧为它预留的边界见 [docs/project-design.md §7.6](../docs/project-design.md#76-为-agent-预留的边界待定)：

- Agent 只通过 core-service 提供的只读工具接口访问数据，不直接连接数据库。
- 异步任务经 Kafka `agent.tasks.high` / `agent.tasks.low` 派发，结果写回 `agent.results`。
