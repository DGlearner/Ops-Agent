# Ops-Agent 项目设计文档（Java 底座）

> **状态：** v0.1 草案
> **日期：** 2026-10-15
> **范围：** 模块划分、接口（HTTP、Kafka 消息）、数据模型、核心处理逻辑、配置、测试与开发计划。
> **不在本文范围：** Agent 部分（Python）的具体功能与实现方式尚未确定，本文只定义 Java 侧需要为它预留的边界（§7.6）。
> **背景：** 本项目是 AIOps v1（Python MVP）的重写。v1 的问题清单和总体技术选型来自前期分析文档，本文只记录与实现直接相关的决定；与分析文档不一致的地方列在 §15。

---

## 1. 项目定位

一个面向**微服务系统值班运维**的平台：采集日志 → 实时检测异常 → 按服务依赖把告警合并为事件（Incident）→ 由 Agent 解释根因、回答运维问题、定时巡检。

- **Java 负责：** 所有确定性、高吞吐的部分，以及系统数据的唯一入口（接入、解析、检测、聚合、Incident、权限、调度、知识库存储）。
- **Python 负责（待定）：** LLM 推理。Agent 不直接访问数据库，只能调用 Java 提供的只读工具接口。
- **被监控对象（待定）：** 计划为一个小型电商微服务靶场（网关、订单、库存、支付 + MySQL + Redis + Kafka），可注入故障，用来产生带标准答案的评测数据。

## 2. 本阶段范围

| 做 | 暂不做 |
|---|---|
| Gradle 多项目工程、版本锁定、CI、本地基础设施 | Agent 推理、RAG、巡检逻辑（等 Agent 方案确定） |
| `ingest-service`：HTTP 批量接入、API Key 鉴权、限流、写 Kafka | WebSocket 持续推送接入（J5） |
| `stream-processor`：解析、脱敏、规则检测、去重、告警聚合 | 学习型异常检测 |
| `core-service`：身份与权限、服务目录与拓扑、日志源、规则管理、告警与 Incident、Outbox | 知识库、对话、巡检计划、Agent 任务派发（预留接口） |
| 单元测试、Kafka Streams 拓扑测试、Testcontainers 集成测试 | 前端控制台（后续单独规划） |

---

## 3. 总体架构

```
 log shippers / replay tool / demo-target
        |  HTTP POST /api/v1/logs  (X-Api-Key)
        v
 +------------------+      logs.raw (key = host)
 |  ingest-service  | ----------------------------+
 |  auth, limit     |                             |
 +------------------+                             v
                                    +---------------------------+
   rules.changelog (compact) -----> |     stream-processor      |
   topology.changelog (compact) --> |  Kafka Streams            |
                                    |  parse -> redact          |
                                    |  -> rule match            |
                                    |  -> sliding-window count  |
                                    |  -> dedup -> alerts       |
                                    |  -> re-key by dep. group  |
                                    |  -> session-window merge  |
                                    +---------------------------+
                                      | logs.normalized  | alerts
                                      | logs.unparsed    | incident.candidates
                                      v                  v
 +--------------------------------------------------------------+
 |  core-service  (Spring Boot, Spring Modulith)                |
 |  identity | catalog | rules | alerting | incident | (tools)  |
 |  PostgreSQL + pgvector, Redis                                |
 |  outbox -> incident.events, rules.changelog,                 |
 |            topology.changelog                                |
 +--------------------------------------------------------------+
        ^  REST + SSE                     |  agent.tasks.*  ^ agent.results
        |                                 v                 |
 [web console] (later)            [agent-runtime, Python] (TBD)
                                  calls core-service tools (read-only)
```

**数据流约定：**

- 规则和服务拓扑的"事实来源"在 core-service 的数据库里。变更通过 Outbox 写入两个 compacted topic，stream-processor 以 GlobalKTable 加载，实现热更新。
- stream-processor 只读写 Kafka，不连数据库，因此可以独立扩容和重放。
- core-service 消费 `alerts` 和 `incident.candidates`，按业务键幂等落库。

---

## 4. 仓库结构

```
Ops-Agent/
  build-logic/              Gradle convention plugins (shared build config)
  gradle/libs.versions.toml dependency versions (single source of truth)
  libs/
    ops-contracts/          Kafka message records, JSON mapper, IDs
  services/
    ingest-service/
    stream-processor/
    core-service/
  contracts/
    kafka/                  JSON Schema for every Kafka message (shared with Python)
  agent/                    Python agent runtime (TBD)
  deploy/compose/           local infrastructure (Kafka, PostgreSQL, Redis)
  datasets/                 sample logs for replay and tests
  tools/                    replay tool and other dev scripts
  docs/
```

---

## 5. 技术栈与版本

版本在 `gradle/libs.versions.toml` 和 `deploy/compose/compose.yaml` 中锁定，下表为 2026-10-15 的选择。

| 领域 | 选择 | 版本 |
|---|---|---|
| 语言 | Java（Temurin） | 21 LTS |
| 构建 | Gradle（Kotlin DSL）+ wrapper；foojay 插件自动下载 JDK | 9.8.1 |
| 框架 | Spring Boot（含 Spring Framework 7、Spring Security 7、Jackson 3） | 4.1.1 |
| 模块化 | Spring Modulith（core-service 模块边界校验与事件发布登记） | 2.1.1 |
| 消息 | Kafka（KRaft 单节点）；客户端随 Spring Boot 管理（Kafka 4.2.1、Spring Kafka 4.1.1） | broker 4.2.2 |
| 流处理 | Kafka Streams（`exactly_once_v2`） | 4.2.1 |
| 数据库 | PostgreSQL + pgvector；Flyway 管理迁移 | PG 18.6，pgvector 0.8.7 |
| 持久层 | MyBatis-Plus（2026-10-17 确定，§14 D1）：手写 SQL 与 CRUD 辅助并存，国内岗位最常见 | 3.5.17（Boot 4 starter） |
| 缓存与状态 | Redis（Lettuce 客户端） | 8.8.3 |
| API 文档 | springdoc-openapi | 3.1.1 |
| 测试 | JUnit 6、AssertJ、Testcontainers 2、Kafka Streams TopologyTestDriver | 随 Boot 管理 |
| 可观测 | Spring Boot Actuator、Micrometer + Prometheus | 随 Boot 管理 |

版本选择的考虑：

- **JDK 21：** 选更贴近企业现状的 LTS（2023-09 发布，Temurin 支持到 2029-12）。17 没有虚拟线程，所以不选。
- **Spring Boot 4.1：** 3.x 的最后一个版本 3.5 的开源支持已于 2026-06-30 结束，新项目不再用。4.1 的开源支持到 2027-07。
- **JDK 21 上虚拟线程的限制：** ingest-service 开启了虚拟线程。Kafka 拿不到元数据时，producer 会在 `synchronized` 方法里调用 `Object.wait()`，最长 `max.block.ms`（5 s）。JDK 21 上这会钉住载体线程，调度器临时增加载体线程补偿（上限 256），也就是 Kafka 故障期间每个等待中的请求占一个系统线程。实测（4 个载体、64 个虚拟线程各等待 1 s）：JDK 21 线程峰值 72，JDK 25 为 12，耗时都是 1 s。在 `synchronized` 里做其他阻塞操作（sleep、阻塞 I/O）没有补偿，会被串行化（同样实测 16 s 对 1 s），所以本项目在虚拟线程上用 `ReentrantLock` 而不是 `synchronized` 包住阻塞调用。JDK 24 起（JEP 491）没有这个限制。

---

## 6. 模块划分与职责

### 6.1 `libs/ops-contracts`

所有服务共享的消息契约，不依赖 Spring。

| 内容 | 说明 |
|---|---|
| 消息类型 | `RawLogRecord`、`NormalizedEvent`、`UnparsedLog`、`RuleDefinition`、`TopologyEntry`、`Alert`、`IncidentCandidate`、`IncidentEvent`（Java record） |
| 枚举 | `SourceType`、`Severity` |
| `Topics` | topic 名称常量，与 `deploy/compose/kafka/create-topics.sh` 保持一致 |
| `OpsJson` | 统一的 Jackson 3 `JsonMapper`：snake_case 字段名、ISO-8601 时间、忽略未知字段 |
| `Ids` | UUIDv7 生成（按时间有序，适合做主键和游标） |

JSON Schema 放在仓库根目录 `contracts/kafka/`，供 Python 侧使用；Java 侧有测试保证 record 序列化结果符合 schema。

### 6.2 `services/ingest-service`

| 包 | 职责 |
|---|---|
| `api` | `POST /api/v1/logs`：请求校验、响应 |
| `auth` | API Key 过滤器：按 SHA-256 哈希查找日志源，得到 `source_id` |
| `ratelimit` | Redis 令牌桶（Lua 脚本），按日志源、按**日志条数**限流 |
| `publish` | 拆分为单条 `RawLogRecord`，补充 `event_id`、`received_at`，以 host 为 key 写入 `logs.raw`，等待 broker 确认后才返回 202 |

- **语义：** 返回 202 表示所有记录都已被 Kafka 确认（`acks=all`，幂等生产者）。Kafka 不可用时返回 503，客户端可以重试。
- **重试与去重：** 请求带 `Idempotency-Key` 时，`request_id` 和每条记录的 `event_id` 都由它推导（UUIDv5），重试产生的记录 `event_id` 相同，stream-processor 据此去重（§8.3）；不带时为随机 UUIDv7。重放工具 `tools/Replay.java` 每行都带这个头。
- **限流失败时放行：** Redis 不可用时请求照常接收，并累加 `ops.ingest.ratelimit.errors`。Redis 故障期间丢日志比短暂不限流更糟。
- **日志源与 API Key：** J1 阶段从配置文件读取（只存哈希）；J3 起改为从 core-service 的 `log_sources` 表读取，并在 Redis 中缓存。

### 6.3 `services/stream-processor`

一个 Spring Boot 应用，内含一个 Kafka Streams 拓扑。不连接数据库。

| 子拓扑 | 输入 | 输出 | 说明 |
|---|---|---|---|
| 解析 | `logs.raw` | `logs.normalized`、`logs.unparsed` | 按 `source_type` 选择解析器，之后统一脱敏（§8.1–8.2） |
| 检测 | `logs.normalized` + 规则全局表 | `alerts` | 规则匹配 → 滑动窗口计数 → 达到阈值 → 去重（§8.3） |
| 聚合 | `alerts` + 拓扑全局表 | `incident.candidates` | 按依赖组重分区 → 会话窗口合并（§8.4） |

### 6.4 `services/core-service`

模块化单体（Spring Modulith），每个模块是一个顶层包，模块之间只通过公开的 API 类和领域事件通信，由 `ApplicationModules.verify()` 在测试中校验。

| 模块 | 职责 | 阶段 |
|---|---|---|
| `identity` | 用户、团队、角色；登录与 JWT 签发；权限校验 | J3 |
| `catalog` | 服务、服务依赖、依赖组计算、日志源与 API Key；拓扑变更发布到 `topology.changelog` | J3 |
| `rules` | 规则的增删改查、版本、启停、校验；变更发布到 `rules.changelog` | J3 |
| `alerting` | 消费 `alerts` 并落库；静默规则；告警查询 | 落库与查询已实现（R2，2026-10-17）；静默规则待做 |
| `incident` | 消费 `incident.candidates`；Incident 生命周期状态机、指派、评论、时间线；领域事件发布到 `incident.events` | J4 |
| `shared` | 错误处理（ProblemDetail）、分页、审计字段、Outbox 配置 | J3 |
| `tools`、`agentops`、`knowledge`、`chat`、`scheduling` | 为 Agent 预留，等 Agent 方案确定后设计 | 待定 |

### 6.5 `agent/`（Python，待定）

本阶段只放一个 README。Java 侧为它预留的边界见 §7.6。

---

## 7. 接口设计

### 7.1 HTTP 通用约定

| 项 | 约定 |
|---|---|
| 前缀 | `/api/v1` |
| 格式 | JSON，字段名 snake_case；时间为 ISO-8601 UTC（如 `2026-10-15T08:00:00Z`） |
| 鉴权 | 控制台与管理接口：`Authorization: Bearer <JWT>`；日志接入：`X-Api-Key: <key>` |
| 错误 | RFC 9457 ProblemDetail：`type`、`title`、`status`、`detail`、`instance`，扩展字段 `code`（如 `RULE_INVALID`）和 `errors`（字段级错误） |
| 分页 | 游标分页：`?limit=50&cursor=<opaque>`，响应 `{ "items": [...], "next_cursor": "..." }`。列表按 UUIDv7 主键倒序，游标即最后一条的主键 |
| 幂等 | 日志接入已支持 `Idempotency-Key`（§6.2）；core-service 的创建类 POST 在 J5 支持（Redis 保存 24 小时） |
| 文档 | 每个服务在 `/v3/api-docs` 和 `/swagger-ui.html` 提供 OpenAPI |
| 运维端点 | `/actuator/health`、`/actuator/prometheus` |

### 7.2 ingest-service

**`POST /api/v1/logs`**

请求体与 v1 的数据集格式一致，因此数据集中的每一行可以直接作为一次请求重放：

```json
{
  "source_type": "nginx",
  "host": "web-02",
  "service": "nginx",
  "source_name": "/var/log/nginx/access.log",
  "logs": ["198.51.100.23 - - [01/Jan/2026:00:00:18 +0000] \"GET /api/payments HTTP/1.1\" 200 ..."]
}
```

| 字段 | 必填 | 约束 |
|---|---|---|
| `source_type` | 是 | `nginx`、`linux`、`mysql`、`app_service`、`docker` |
| `host` | 是 | 1–255 字符 |
| `service` | 否 | 1–255 字符 |
| `source_name` | 否 | 日志文件路径等，≤ 1024 字符 |
| `logs` | 是 | 1–1000 条；单条 ≤ 16 KB |

| 请求头 | 必填 | 说明 |
|---|---|---|
| `X-Api-Key` | 是 | 日志源的 API Key |
| `Idempotency-Key` | 否 | 1–128 个可见 ASCII 字符；相同的 key 产生相同的 `event_id`（见 §6.2） |

| 响应 | 含义 |
|---|---|
| `202` | `{ "accepted": 1, "request_id": "..." }`，所有记录已写入 Kafka |
| `400` | 字段校验失败（`errors` 中列出字段） |
| `401` | 缺少或无效的 API Key |
| `413` | 请求体超过 1 MB（包括没有 Content-Length 的分块请求） |
| `429` | 超过限流，带 `Retry-After` 头（秒） |
| `503` | Kafka 不可用，带 `Retry-After` 头 |

### 7.3 core-service

所有接口都需要 JWT。角色：`ADMIN`（全局）；`SRE`、`DEVELOPER`、`VIEWER`（按团队）。

| 模块 | 方法与路径 | 说明 | 权限 |
|---|---|---|---|
| identity | `POST /api/v1/auth/login` | 用户名密码登录，返回 `access_token`、`refresh_token`、`expires_in` | 公开 |
| | `POST /api/v1/auth/refresh` | 刷新 token | 公开（需 refresh token） |
| | `GET /api/v1/auth/me` | 当前用户、团队与角色 | 登录用户 |
| | `GET/POST /api/v1/teams` | 团队列表与创建 | ADMIN |
| | `GET/POST /api/v1/users`，`PUT /api/v1/users/{id}/roles` | 用户管理与授权 | ADMIN |
| catalog | `GET/POST /api/v1/services`，`GET/PUT/DELETE /api/v1/services/{name}` | 服务目录（所属团队、层级、描述） | 写：ADMIN、团队 SRE |
| | `PUT /api/v1/services/{name}/dependencies` | 整体替换某服务的下游依赖 `{ "depends_on": ["mysql"] }` | 同上 |
| | `GET /api/v1/topology` | 节点、边与依赖组 | 登录用户 |
| | `GET/POST /api/v1/log-sources`，`PATCH /api/v1/log-sources/{id}` | 日志源管理与启停 | ADMIN、团队 SRE |
| | `POST /api/v1/log-sources/{id}/keys` | 生成新的 API Key，明文只返回这一次 | 同上 |
| rules | `GET/POST /api/v1/rules`，`GET /api/v1/rules/{id}` | 规则列表、创建、详情 | 写：ADMIN、SRE |
| | `PUT /api/v1/rules/{id}` | 修改规则，生成新版本 | 同上 |
| | `PATCH /api/v1/rules/{id}` | `{ "enabled": false }` 启停 | 同上 |
| | `GET /api/v1/rules/{id}/versions` | 版本历史 | 登录用户 |
| | `POST /api/v1/rules/validate` | 只校验，不保存 | 登录用户 |
| alerting | `GET /api/v1/alerts`，`GET /api/v1/alerts/{id}` | 过滤：`severity`、`service`、`rule_id`、`from`、`to`（已实现）、`incident_id`（随 incident 模块） | 团队范围 |
| | `GET/POST /api/v1/silences`，`DELETE /api/v1/silences/{id}` | 维护期静默 | SRE |
| incident | `GET /api/v1/incidents`，`GET /api/v1/incidents/{id}` | 过滤：`status`、`severity`、`service`、`from`、`to` | 团队范围 |
| | `GET /api/v1/incidents/{id}/alerts`，`GET /api/v1/incidents/{id}/timeline` | 关联告警与时间线 | 团队范围 |
| | `POST /api/v1/incidents/{id}/transitions` | `{ "to_status": "acknowledged", "note": "..." }`，非法迁移返回 409 | SRE、DEVELOPER |
| | `PUT /api/v1/incidents/{id}/assignee` | `{ "user_id": "..." }` | SRE |
| | `POST /api/v1/incidents/{id}/comments` | 评论，写入时间线 | SRE、DEVELOPER |
| 实时推送 | `GET /api/v1/stream` | SSE：告警与 Incident 变更（多实例时经 Redis Pub/Sub 广播） | 登录用户 |

### 7.4 Kafka topic

由 `deploy/compose/kafka/create-topics.sh` 创建；broker 关闭了自动建 topic。所有消息值为 JSON（snake_case），schema 在 `contracts/kafka/`。

| Topic | Key | 值类型 | 分区 | 清理 | 生产者 | 消费者 |
|---|---|---|---|---|---|---|
| `logs.raw` | host | `RawLogRecord` | 6 | 保留 7 天 | ingest | stream-processor |
| `logs.normalized` | service（缺失时用 host） | `NormalizedEvent` | 6 | 3 天 | stream-processor | stream-processor（检测）、core（J5，日志检索） |
| `logs.unparsed` | source_type | `UnparsedLog` | 1 | 7 天 | stream-processor | 离线分析 |
| `rules.changelog` | rule_id | `RuleDefinition`（删除为 tombstone） | 1 | compact | core（outbox） | stream-processor |
| `topology.changelog` | service | `TopologyEntry` | 1 | compact | core（outbox） | stream-processor |
| `alerts` | group_key | `Alert` | 6 | 7 天 | stream-processor | stream-processor（聚合）、core |
| `incident.candidates` | group_key | `IncidentCandidate` | 6 | 7 天 | stream-processor | core |
| `incident.events` | incident_id | `IncidentEvent` | 3 | 30 天 | core（outbox） | 推送、Agent 派发（待定） |
| `agent.tasks.high` / `.low`、`agent.results` | task_id | 待定 | 3 | 3 / 7 天 | core / agent | agent / core |
| `<topic>.dlt` | 原 key | 原消息 + 错误头 | 1 | 14 天 | Spring Kafka 错误处理器 | 人工排查、重放 |
| `stream-processor.dlt` | 原 key | 原始字节 + 错误头 | 1 | 14 天 | Kafka Streams（`errors.dead.letter.queue.topic.name`） | 人工排查 |

**生产与消费语义：**

- 生产者：`acks=all`，幂等开启。
- Kafka Streams：`processing.guarantee=exactly_once_v2`，状态与 offset 一起提交；无法反序列化的记录跳过并写入 `stream-processor.dlt`（Kafka Streams 4.2 的内置死信队列）。读取 Streams 输出的消费者应使用 `isolation.level=read_committed`。
- core 的消费者：手动提交；按业务键幂等 upsert；`DefaultErrorHandler` 指数退避重试 3 次后写入 `<topic>.dlt`。

### 7.5 消息字段

完整定义见 `contracts/kafka/*.schema.json`。以下列出主要字段。

**`RawLogRecord`**（`logs.raw`）

| 字段 | 类型 | 说明 |
|---|---|---|
| `event_id` | UUIDv7 | ingest 生成，全链路唯一 |
| `request_id` | UUIDv7 | 同一次 HTTP 请求的记录共享 |
| `source_id` | string | 来自 API Key 对应的日志源 |
| `source_type` | enum | 见 §7.2 |
| `host`、`service`、`source_name` | string | 来自请求体 |
| `message` | string | 原始日志行 |
| `received_at` | timestamp | ingest 接收时间 |

**`NormalizedEvent`**（`logs.normalized`）：`event_id`、`source_id`、`source_type`、`source_name`、`host`、`service`、`component`、`event_type`、`severity`、`timestamp`（日志自身时间，无法解析时用 `received_at`）、`received_at`、`message`（已脱敏）、`fields`（解析出的键值）、`tags`。

**`UnparsedLog`**（`logs.unparsed`）：原始记录 + `reason`。

**`RuleDefinition`**（`rules.changelog`）：见 §8.3。

**`TopologyEntry`**（`topology.changelog`）：`service`、`group_key`（依赖组，由 core 计算）、`depends_on`、`team`、`version`。

**`Alert`**（`alerts`）

| 字段 | 说明 |
|---|---|
| `alert_id` | 由 `fingerprint` + 首次越过阈值的窗口起点生成的确定性 UUID，重放时不变，保证幂等 |
| `rule_id`、`rule_version` | 触发的规则 |
| `fingerprint` | `rule_id` + 分组值，例如 `ssh-brute-force|host=app-01|src_ip=198.51.100.240` |
| `severity` | 规则定义的级别 |
| `group_key` | 依赖组（无拓扑信息时为 service，再缺失时为 host） |
| `service`、`host` | 来自触发事件 |
| `window_start`、`window_end`、`count`、`threshold` | 窗口与计数 |
| `first_event_at`、`last_event_at` | 窗口内首尾事件时间 |
| `sample_event_ids` | 最多 5 个触发事件 |
| `samples` | 这些事件本身，作为证据：`event_id`、`timestamp`、`message`（已脱敏，截断到 500 字符）、`trace_id`（结构化应用日志才有）。事件不另外存储，告警里没有证据就无从查起 |
| `group_values` | 分组字段的取值 |
| `summary` | 按规则模板生成的描述 |
| `created_at` | 产生时间 |

**`IncidentCandidate`**（`incident.candidates`）：`group_key`、`session_start`、`session_end`、`alert_ids`、`services`、`hosts`、`max_severity`、`alert_count`、`merged_from`（被合并掉的旧会话起点，供 core 合并已存在的 Incident）。

**`IncidentEvent`**（`incident.events`）：`event_id`、`incident_id`、`type`（`OPENED`、`ALERT_ATTACHED`、`STATUS_CHANGED`、`ASSIGNED`、`COMMENTED`、`MERGED`）、`actor_type`（`USER`、`SYSTEM`、`AGENT`）、`actor_id`、`from_status`、`to_status`、`payload`、`occurred_at`。

### 7.6 为 Agent 预留的边界（待定）

Agent 方案确定前，Java 侧只承诺以下原则，不实现具体接口：

1. Agent 只能通过 core-service 提供的**只读工具接口**访问数据（候选工具：日志检索、指标查询、告警与 Incident 详情、拓扑、最近部署、知识库检索）。
2. 异步任务通过 `agent.tasks.high` / `agent.tasks.low` 派发，结果写回 `agent.results`，由 core 校验后落库。
3. 每个任务携带短期、限定范围的任务 token；工具调用经过鉴权、限流、超时、脱敏，并写入审计表。

---

## 8. 核心处理逻辑

### 8.1 解析

解析器按 `source_type` 选择，按顺序尝试匹配，第一个命中的规则决定 `event_type`。所有解析器都用 `datasets/` 中的样例日志作为测试数据。

| source_type | 格式 | 主要 event_type |
|---|---|---|
| `nginx` | combined 格式 + `upstream`、`request_time` | `http_request`；状态码 ≥ 500 为 `http_server_error`（medium）；`request_time` ≥ 1 s 标记 `slow` |
| `linux` | syslog（`Mon DD HH:MM:SS host prog[pid]: body`） | `ssh_login_failed`、`ssh_invalid_user`（low）；`ssh_login_success`；`sudo_command`；`cron_job`；`systemd_event`；`kernel_oom`（high）；其余为 `syslog` |
| `mysql` | syslog 前缀 + MySQL 消息 | `mysql_connection_refused`、`mysql_too_many_connections`（high）；`mysql_access_denied`（low）；`mysql_slow_query`（latency ≥ 1 s 或 `Query_time` ≥ 1，medium）；`mysql_query`；`mysql_ready` |
| `app_service` | `ISO时间 LEVEL key=value ...`（logfmt） | `app_db_connection_error`（有 `db_host` 且错误含 connection refused，medium）；`app_http_server_error`（`status` ≥ 500）；`app_login_failed`；其余 `app_log`，级别由 LEVEL 决定 |
| `app_service` | 每行一个 ECS JSON 对象（以 `{` 开头的行；Spring Boot 的结构化日志，如电商靶场） | `event.action` 本身（如 `dependency_call_failed`、`database_unavailable`，见 shop-design §8.1）；没有时按级别取 `app_error`（medium）、`app_warning`（low）、`app_log`。`message` 作为消息；其余字段展平成带点的名字（`trace.id`、`error.type`、`order_id`），去掉进程信息和堆栈 |
| `docker` | `ISO时间 dockerd[pid]: key=value ...` | `container_oom_killed`（high）；`container_died`（exitCode ≠ 0，medium）；`container_restarted`（medium）；`container_unhealthy`（medium）；其余 `container_event` |

- **时间：** syslog 不带年份，用 `received_at` 的年份补全（跨年时取离 `received_at` 最近的年份）。
- **无法解析：** 写入 `logs.unparsed` 并带上原因，不静默丢弃。JSON 行解析失败或缺少 `@timestamp` 也算无法解析。
- **对 v1 的修正：** v1 只接受 `linux`、`mysql`、`app_service` 三类，样例数据中约 32% 的 nginx 和 docker 日志在接入时就会被拒绝；v1 的 app 日志正则要求"时间 服务名 级别"的顺序，与样例数据"时间 级别 key=value"的格式不符，导致应用侧的数据库连接错误无法被识别。

### 8.2 脱敏

在解析之后、写出之前执行，作用于 `message` 和所有字符串字段；`logs.normalized` 和 `logs.unparsed` 两个出口都经过这一步。

| 类型 | 匹配 | 替换 |
|---|---|---|
| 键值对 | `password`、`passwd`、`secret`、`token`、`api_key`、`access_key` 等键（不区分大小写，支持 `=` 和 `:`，键和值可以带引号，值里可以有转义的引号；引号没有闭合时，比如日志在值中间被截断，一直遮蔽到末尾）。`PWD` 不算：sudo 日志里它是工作目录 | 值替换为 `***`，原来带引号的仍带引号 |
| HTTP 头 | `Authorization: Bearer ...`、`Basic ...`，包括 JSON 里的 `"Authorization": "Bearer ..."` | 凭据替换为 `***` |
| 已知格式 | JWT（`eyJ...` 三段）、AWS Access Key（`AKIA...`）、URL 中的 `user:password@` | `***` |

值的边界用逐字符扫描确定，而不用正则：处理带转义的引号字符串时，`java.util.regex` 每个字符递归一层，接入端允许的 16 KB 长行足以让流处理线程栈溢出。

整条消息是 JSON 文档时（结构化的应用日志通常如此），先解析，再逐个字段处理：名字是密钥的字段（包括 `authorization`）整个替换为 `***`，其他字符串值按上表处理，嵌套对象和数组同样处理。结果仍是合法的 JSON；没有需要遮蔽的内容时原样保留。解析失败的按普通文本处理，不会因为解析失败而原样放行。字段（`fields`）里的嵌套对象和列表也逐层处理。

`logs.raw` 保存的是脱敏之前的原文，所以“下游已脱敏”不等于 Kafka 里从没有过明文：要控制 `logs.raw` 的访问权限和保留期限，Agent 也不读这个 topic。

### 8.3 规则检测

**规则定义（JSON）：**

```json
{
  "rule_id": "ssh-brute-force",
  "version": 1,
  "enabled": true,
  "name": "SSH 暴力破解",
  "match": {
    "event_types": ["ssh_login_failed", "ssh_invalid_user"],
    "source_types": ["linux"],
    "field_equals": {}
  },
  "group_by": ["host", "fields.src_ip"],
  "window": "PT5M",
  "threshold": 5,
  "severity": "medium",
  "suppress_for": "PT10M",
  "summary_template": "{count} failed SSH logins from {fields.src_ip} on {host} within {window}"
}
```

- **匹配：** `event_types`、`source_types`、`min_severity`、`field_equals` 之间为"与"关系，未给出的条件视为不限。
- **分组：** `group_by` 可以使用 `host`、`service`、`component`、`event_type` 和 `fields.<name>`。
- **窗口：** 限定为 `PT1M`、`PT5M`、`PT15M` 三档。Kafka Streams 的窗口大小在拓扑构建时确定，三档各对应一条子拓扑；规则按自己的档位路由。这是为了支持规则热更新而做的取舍。
- **计数：** `SlidingWindows`，宽限期 30 秒（允许轻微乱序）。默认使用事件时间；`ops.stream.detection-time=ingest` 改用接收时间，用于反复重放旧数据集（用事件时间时，流时间一旦越过数据集，再次重放会被当作迟到数据丢弃）。
- **重复事件：** 同一条日志投递多次（例如客户端带同一个 `Idempotency-Key` 重试，§6.2）只计一次。计数前按 `fingerprint` + `event_id` 丢掉重复的匹配；只记匹配过规则的事件，保留窗口加宽限期的时长，相隔更久的副本不可能落进同一个窗口。Kafka 的 exactly-once 只防止流处理自身产生的重复，代替不了这一步。
- **告警抑制：** 同一个 `fingerprint` 在 `suppress_for` 时间内只发一次告警。抑制期限、窗口和过期清理都用同一个检测时间（由 `detection-time` 决定是事件时间还是接收时间），日志自身的时间只作为告警里的证据。抑制状态保存在 Kafka Streams 的状态存储中，与 offset 一起提交，重启或重放时不会重复告警；过期的记录按流时间定期清理。
- **热更新：** 规则来自 `rules.changelog` 的全局表；规则变更后，新进入的事件立即按新规则匹配。
- **默认规则（从 v1 移植并扩充，见 `services/stream-processor/src/main/resources/rules/default-rules.json`）：** SSH 暴力破解（5 分钟 ≥ 5，按 host + 来源 IP）；MySQL 连接被拒绝（1 分钟 ≥ 1）；MySQL 连接数过多；MySQL 慢查询（5 分钟 ≥ 3）；应用数据库连接错误（5 分钟 ≥ 2，按 service + host）；Nginx 5xx（5 分钟 ≥ 5，按 host）；容器 OOM（≥ 1）；容器频繁重启（15 分钟 ≥ 3，按容器）；内核 OOM；磁盘使用告警（node-exporter 报告 warning 或 critical）。电商靶场（按 `event.action`）：服务连不上数据库（1 分钟 ≥ 3，按服务）；调用其他服务失败（1 分钟 ≥ 10，按服务 + 被调用方）；未处理的异常（≥ 1）；库存结算被拒、需要人工处理（≥ 1）；后台恢复步骤反复失败（5 分钟 ≥ 3）。售罄等正常业务结果不设规则。
- **样例数据的结果：** 重放 `datasets/aiops_mixed_incident_small.jsonl` 产生 7 条告警：SSH 暴力破解、MySQL 连接被拒绝、MySQL 连接数过多、payment-api 数据库连接错误、web-01 的 5xx 突增、api-gateway 容器 OOM、api-gateway 频繁重启（拓扑测试和真实 Kafka 上的端到端运行结果一致）。
- **规则来源：** J3 之前没有 core-service，stream-processor 在 `local` 配置下启动时把 `default-rules.json` 写入 `rules.changelog`；J3 起由 core-service 管理。

### 8.4 告警聚合

1. **依赖组：** core-service 把服务依赖图的每个连通分量算作一个依赖组，`group_key` 取组内的根服务名（没有上游依赖的服务；多个时取字典序最小者）。结果按服务写入 `topology.changelog`。
2. **重分区：** stream-processor 收到告警后，按 `service`（缺失时按 host）查拓扑全局表得到 `group_key`，以它为新 key 重分区。查不到时 `group_key` 取服务名自身。
3. **会话窗口：** 在 `group_key` 上做 `SessionWindows`（间隔 5 分钟），聚合为 `IncidentCandidate`，每次更新都输出。
4. **会话合并：** 两个会话因新告警而合并时，输出的候选带 `merged_from`，core 据此把旧 Incident 合并进新 Incident。
5. **效果：** MySQL 不可用 → 应用数据库连接错误 → Nginx 5xx，这三类告警会进入同一个 Incident。

### 8.5 Incident 生命周期

```
open -> acknowledged -> investigating -> mitigated -> resolved -> closed
  |          |               |              |
  +----------+---------------+--------------+--> resolved   (can resolve from any active state)
resolved -> open    (reopen, only before closed)
```

- 迁移由状态机校验，非法迁移返回 409。
- 每次变化写入 `incident_events`，并通过 Outbox 发布到 `incident.events`。
- 乐观锁（`version` 字段）防止并发修改相互覆盖。

### 8.6 Outbox

使用 Spring Modulith 的事件发布登记（`event_publication` 表）：业务数据与待发布事件在同一事务内写入，事务提交后发布到 Kafka，失败的事件在重启后重新发布。拓扑、规则和 Incident 事件都走这条路径，解决 v1 中"先写数据库再写 Kafka"的双写不一致问题。

---

## 9. 数据模型（core-service）

PostgreSQL，schema `core`，Flyway 管理。主键为 UUIDv7（`uuid` 类型），时间字段为 `timestamptz`。

| 表 | 主要字段 | 阶段 |
|---|---|---|
| `teams` | `id`、`name`（唯一）、`created_at` | J3 |
| `users` | `id`、`username`（唯一）、`display_name`、`password_hash`（BCrypt）、`status`、`created_at` | J3 |
| `user_roles` | `user_id`、`team_id`（ADMIN 时为空）、`role` | J3 |
| `refresh_tokens` | `id`、`user_id`、`token_hash`、`expires_at`、`revoked_at` | J3 |
| `services` | `id`、`name`（唯一）、`team_id`、`tier`、`description` | J3 |
| `service_dependencies` | `service_id`、`depends_on_id`（联合主键） | J3 |
| `log_sources` | `id`、`name`、`source_type`、`team_id`、`enabled`、`last_seen_at` | J3 |
| `log_source_keys` | `id`、`source_id`、`key_hash`、`created_at`、`revoked_at` | J3 |
| `rules` | `id`（rule_id）、`name`、`team_id`、`current_version`、`enabled` | J3 |
| `rule_versions` | `rule_id`、`version`、`definition`（jsonb）、`created_by`、`created_at` | J3 |
| `alerts` | `id`、`rule_id`、`rule_version`、`fingerprint`、`severity`、`group_key`、`service`、`host`、`window_start`、`window_end`、`count`、`threshold`、`first_event_at`、`last_event_at`、`summary`、`group_values`（jsonb）、`sample_event_ids`（jsonb）、`samples`（jsonb，证据）、`incident_id`、`created_at`、`stored_at` | 已实现（R2） |
| `silences` | `id`、`matcher`（jsonb）、`starts_at`、`ends_at`、`reason`、`created_by` | J4 |
| `incidents` | `id`、`title`、`status`、`severity`、`group_key`、`session_start`、`session_end`、`services`（text[]）、`team_id`、`assignee_id`、`opened_at`、`acknowledged_at`、`resolved_at`、`closed_at`、`version` | J4 |
| `incident_alerts` | `incident_id`、`alert_id` | J4 |
| `incident_events` | `id`、`incident_id`、`type`、`actor_type`、`actor_id`、`from_status`、`to_status`、`payload`（jsonb）、`created_at` | J4 |
| `event_publication` | Spring Modulith 管理 | J3 |

- 幂等：`alerts.id` 为消息中的 `alert_id`，写入用 `ON CONFLICT (id) DO NOTHING`；`incidents` 在 `(group_key, session_start)` 上有唯一约束。
- **告警模块的实现（R2）：** 消费 `alerts` 时用 `read_committed`（stream-processor 以 exactly-once 事务写入）。读不懂的消息立即转入 `alerts.dlt`；其他失败（典型的是数据库不可用）按 1 s 起、翻倍到 30 s 的退避一直重试，期间这个分区暂停，告警不会因为停库而丢失。查询用 MyBatis-Plus 的 Lambda 条件构造器；分页按 `(created_at, id)` 做键集分页，因为告警 id 由内容推导（UUIDv5），不是按时间递增的 UUIDv7，游标里同时带上最后一条的创建时间和 id。
- **暂时的访问边界：** identity 模块（JWT）完成之前，core-service 只监听 127.0.0.1，接口不做认证。
- 后续（Agent 相关）的表在 Agent 方案确定后补充。

---

## 10. Redis 使用（本阶段）

Redis 不保存事实数据，所有键都可以重建。

| 键 | 类型 | TTL | 用途 | 阶段 |
|---|---|---|---|---|
| `rl:ingest:{source_id}` | Hash（令牌桶，Lua 原子更新） | 自动过期 | 接入限流（按条数） | J1 |
| `ingest:key:{sha256}` | String | 5 分钟 | API Key → 日志源缓存 | J3 |
| `rl:api:{user_id}` | Hash（令牌桶） | 自动过期 | 管理接口限流 | J5 |
| `idem:{key}` | String | 24 小时 | `Idempotency-Key` | J5 |
| `cache:incident:{id}` | String | 10 分钟 | Incident 详情缓存，收到变更事件时删除 | J4 |
| `ws:events` | Pub/Sub | — | SSE 多实例广播 | J4 |

Redis 配置为 `noeviction`（锁和预算类的键不能被淘汰）。

---

## 11. 配置与端口

| 组件 | 端口 | 说明 |
|---|---|---|
| core-service | 8080 | REST、SSE、Actuator |
| ingest-service | 8081 | 日志接入、Actuator |
| stream-processor | 8082 | 只有 Actuator |
| Kafka | 9092 | 宿主机访问；容器之间用 `kafka:29092` |
| PostgreSQL | 5432 | 库 `opsagent`，用户 `opsagent` |
| Redis | 6379 | — |
| Kafka UI | 8089 | `--profile tools` 时启动 |

- **Spring profiles：** `local`（默认，连接本机的 Compose 基础设施）、`test`（Testcontainers）、`docker`（以后服务也放进 Compose 时使用）。
- **敏感配置：** 只通过环境变量或不提交的 `application-local.yaml` 覆盖；仓库里只有开发用默认值。
- 所有端口在 Compose 中只绑定 127.0.0.1。

---

## 12. 开发与运行

**两种环境：**

| 环境 | 用途 |
|---|---|
| Linux 服务器（开发机） | 写代码、编译、单元测试、单个服务的集成测试 |
| Windows + IntelliJ IDEA + Docker Desktop（WSL2） | 运行完整系统、调试、演示 |

**步骤（两边相同）：**

```bash
# 1. 启动基础设施
cd deploy/compose
docker compose up -d            # Kafka UI: docker compose --profile tools up -d

# 2. 构建与测试（Windows 用 gradlew.bat）
./gradlew build

# 3. 运行某个服务（IDEA 中也可以直接运行主类）
./gradlew :services:ingest-service:bootRun

# 4. 重放样例数据
java tools/Replay.java datasets/aiops_mixed_incident_small.jsonl
```

- **Windows 兼容：** `.gitattributes` 强制 LF（容器内的 shell 脚本不能是 CRLF）；开发脚本使用 Java 单文件程序或 Gradle 任务，不依赖 bash；Gradle 的 foojay 插件会自动下载 JDK 21。
- **内存预算：** 开发服务器只有 5.8 GB 内存。2026-10-16，一次 Gradle 构建、Testcontainers 和开发用的容器同时运行，耗尽内存，服务器卡死了约 15 分钟。现在的限制如下：
  - 只用一个 Gradle 守护进程：Kotlin 在守护进程内编译（`kotlin.compiler.execution.strategy=in-process`），不再多开一个约 0.9 GB 的 Kotlin 守护进程。
  - 测试任务轮流执行：共享的构建服务 `TestSlot` 只有一个位置，同一时间只有一个测试 JVM 和它的容器。编译仍然并行。
  - 测试 JVM 堆 512 MB；测试用的 Kafka 堆 256 MB、容器上限 640 MB；测试用的 MySQL 关闭 Performance Schema、容器上限 512 MB。
  - 两个 Compose 文件里的每个容器都有 `mem_limit`。
  - `bootRun` 的堆上限：Ops-Agent 服务 512 MB，靶场服务 256 MB。
  - 实测（2026-10-16）：在开发服务器上完整执行 `./gradlew build`（90 个测试，约 50 秒），可用内存最低 1.4 GB，没有用到 swap。
  - 开发服务器本机另有 4 GB swap 和 earlyoom，用 `memcap` 包装命令，在 cgroup 里限制构建的内存。这些不在仓库里。

---

## 13. 测试策略

| 层级 | 工具 | 覆盖 |
|---|---|---|
| 单元 | JUnit 6、AssertJ | 解析器（样例日志为测试数据）、脱敏、规则匹配、状态机 |
| 拓扑 | Kafka Streams `TopologyTestDriver` | 窗口计数、阈值、去重、乱序、规则热更新、会话合并 |
| 契约 | JSON Schema 校验 | record 序列化结果符合 `contracts/kafka/` |
| 集成 | Testcontainers（Kafka、PostgreSQL、Redis，`@ServiceConnection`） | 接入 → Kafka；消费 → 落库；Outbox 发布 |
| 架构 | Spring Modulith `ApplicationModules.verify()` | core-service 模块边界 |
| CI | GitHub Actions | 每次 push 和 PR 运行 `./gradlew build` |

---

## 14. 开发计划与待定事项

### 14.1 Java 底座的阶段划分

| 阶段 | 内容 | 完成标准 |
|---|---|---|
| J0 工程骨架 | Gradle 多项目、版本目录、convention plugins、CI、Compose、contracts 模块 | `./gradlew build` 通过；CI 绿 |
| J1 接入 | ingest-service 全部功能 + 重放工具 | 样例数据全部写入 `logs.raw`；限流、鉴权、503 有测试 |
| J2 解析与检测 | stream-processor 的解析、脱敏、检测、去重 | 样例数据中的故障产生预期告警（拓扑测试 + 端到端） |
| J3 core 基础 | identity、catalog、rules、Outbox、错误处理、OpenAPI | 规则和拓扑变更经 Outbox 到达 stream-processor |
| J4 告警与 Incident | 告警聚合（stream）、alerting、incident、SSE | UC1（MySQL 级联故障）合并为 1 个 Incident |
| J5 加固 | WebSocket 接入、幂等键、管理接口限流、指标看板、性能基准 | 吞吐与时延基准出数 |

### 14.2 待定事项

| 编号 | 事项 | 影响 | 默认方案 |
|---|---|---|---|
| D1 | 持久层：MyBatis-Plus（国内岗位更常见）或 Spring Data JPA（海外更常见） | core-service（J3 起） | **已定：MyBatis-Plus**（2026-10-17） |
| D2 | Agent 部分的功能与实现方式 | §7.6 的边界、agent 相关表与 topic 的消息格式 | 待定 |
| D3 | 被监控的业务是否采用电商微服务靶场；是否接入真实服务器日志作为第二个环境 | 数据集、默认规则、评测 | 电商靶场 |
| D4 | 中文全文检索（zhparser / pg_jieba）还是 OpenSearch | 知识库与日志检索 | 与 D2 一起决定 |

---

## 15. 与前期分析文档的差异

| 项 | 分析文档 | 本文 | 原因 |
|---|---|---|---|
| 仓库 | 在 AIOps 仓库内重组为 monorepo | 新仓库 Ops-Agent | 与 v1 代码分开，历史更清晰 |
| 告警去重 | Redis `SET NX EX` | Kafka Streams 状态存储 | 与 offset 一起提交，重启和重放时不重复告警；不需要在流处理中访问外部系统 |
| 窗口大小 | 规则任意配置 | 1 / 5 / 15 分钟三档 | Kafka Streams 窗口大小在构建拓扑时固定，热更新规则时只能在固定档位间切换 |
| 接入请求格式 | 未规定 | 与 v1 数据集格式一致 | 数据集可以直接重放 |
| 字段命名 | 未规定 | HTTP 与 Kafka 统一 snake_case | Python 侧直接使用，无需转换 |
