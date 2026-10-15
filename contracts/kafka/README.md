# Kafka 消息契约

每个 topic 的消息值都是 JSON（字段名 snake_case，时间为 ISO-8601 UTC，时长为 ISO-8601 duration，如 `PT5M`）。
这里的 JSON Schema（draft 2020-12）是 Java 与 Python 之间的约定；Java 侧的 record 在
`libs/ops-contracts` 中，并有测试保证序列化结果符合这些 schema。

兼容规则：只增加可选字段，不删除、不改名、不改类型。消费者忽略不认识的字段。

| 文件 | Topic |
|---|---|
| `raw-log-record.schema.json` | `logs.raw` |
| `normalized-event.schema.json` | `logs.normalized` |
| `unparsed-log.schema.json` | `logs.unparsed` |
| `rule-definition.schema.json` | `rules.changelog` |
| `topology-entry.schema.json` | `topology.changelog` |
| `alert.schema.json` | `alerts` |
| `incident-candidate.schema.json` | `incident.candidates` |
