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
| `stream-processor`：解析、脱敏、规则检测、去重 | 学习型异常检测（基线异常检测见 §14.3 P2） |
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
                                    |  Kafka Streams            |
                                    |  parse -> redact          |
                                    |  -> rule match -> dedup   |
                                    |  -> sliding-window count  |
                                    |  -> suppression -> alerts |
                                    +---------------------------+
                                      | logs.normalized  | alerts
                                      | logs.unparsed    |
                                      v                  v
 +--------------------------------------------------------------+
 |  core-service  (Spring Boot, Spring Modulith)                |
 |  catalog | alerting -> incident -> agentops | (rules, ...)   |
 |  alerts correlated into incidents along the catalog (§8.4)   |
 |  PostgreSQL + pgvector, Redis                                |
 |  outbox -> incident.events, rules.changelog                  |
 +--------------------------------------------------------------+
        ^  REST + SSE                     |  agent.tasks.*  ^ agent.results
        |                                 v                 |
 [web console, Vue 3]             [agent-runtime, Python]
                                  reads core-service's API (read-only)
```

**数据流约定：**

- 规则和服务拓扑的"事实来源"在 core-service 的数据库里。规则变更通过 Outbox 写入 compacted topic，stream-processor 以 GlobalKTable 加载，实现热更新。服务拓扑只在 core 内部用于告警关联（§8.4），不再发给 stream-processor。
- stream-processor 只读写 Kafka，不连数据库，因此可以独立扩容和重放。
- core-service 消费 `alerts`，按告警 id 幂等落库，再在 core 内部把告警关联成 Incident（§8.4）。

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
  agent/                    Python agent runtime: incident analysis (§6.5)
  web/                      Ops-Agent console, Vue 3 + TypeScript (§6.6)
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
| 前端（`web/`） | Vue 3 + TypeScript + Vite；Element Plus；ECharts；Vitest | Vue 3.5.43、TS 6.0.3、Vite 8.3.2、Element Plus 2.14.7、ECharts 6.1.0 |

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
| ~~聚合~~ | — | — | 不再在 stream-processor 中做，改在 core-service 关联（§8.4） |

### 6.4 `services/core-service`

模块化单体（Spring Modulith），每个模块是一个顶层包，模块之间只通过公开的 API 类和领域事件通信，由 `ApplicationModules.verify()` 在测试中校验。

| 模块 | 职责 | 阶段 |
|---|---|---|
| `identity` | 用户、团队、角色；登录与 JWT 签发；权限校验 | J3 |
| `catalog` | 服务、服务依赖、日志源与 API Key | 服务与依赖已实现（R3）；日志源与 API Key 待做 |
| `rules` | 规则的增删改查、版本、启停、校验；变更发布到 `rules.changelog` | J3 |
| `alerting` | 消费 `alerts` 并落库；静默规则；告警查询 | 落库与查询已实现（R2，2026-10-17）；静默规则待做 |
| `incident` | 把告警关联成 Incident（§8.4）；生命周期状态机、时间线；以后加指派、评论，领域事件发布到 `incident.events` | 关联、状态机、时间线与查询已实现（R3，2026-10-17） |
| `shared` | 错误处理（ProblemDetail）、分页、审计字段、Outbox 配置 | J3 |
| `agentops` | 向 Agent 派发 Incident 分析任务（自动或手动）；校验 Agent 的结论并落库；查询（§7.6） | 已实现（P4 第一版，2026-10-17） |
| `tools`、`knowledge`、`chat`、`scheduling` | 为 Agent 预留：工具调用的鉴权与审计、知识库、问答、巡检 | 待定 |

### 6.5 `agent/`（Python）

Agent 运行时（`agent/README.md`）。从 `agent.tasks.high` 取任务，通过 core-service 的只读接口查看 Incident，结论写回 `agent.results`。

| 模块 | 作用 |
|---|---|
| `worker` | Kafka 消费与生产；结果送达后才提交任务的 offset；读不懂的任务转入 `agent.tasks.dlt`；过了截止时间的任务跳过 |
| `analyst` | 工具调用循环：模型最多调用 8 次工具，用完后只给它 `submit_analysis`；格式不对的提交退回改正；引用了本 Incident 没有的证据，退回一次 |
| `tools` | 5 个只读工具，都映射到 core-service 已有的接口：本 Incident 的告警（含样本日志）、时间线、拓扑、前后时间段的告警、同时段的其他 Incident。结果只保留分析需要的字段 |
| `llm` | 任何兼容 OpenAI Chat Completions、支持工具调用的接口（配置见 README）；不支持指定 `tool_choice` 的接口自动退回 `auto` |
| `baseline` | 不用大模型的对照：直接取 Incident 的共同依赖或告警指向作为答案 |
| `contracts` | 按 `contracts/kafka/` 的 schema 校验收发的消息，与 Java 端共用 |

### 6.6 `web/`（控制台）

值班人员用的页面（`web/README.md`），Vue 3 + TypeScript + Vite，Element Plus 和 ECharts。只调用 core-service 的 REST 接口；开发服务器把 `/api` 转发给 core-service，浏览器只面对一个源，不需要 CORS。

| 页面 | 作用 |
|---|---|
| Incident 列表 | 筛选（状态、严重度、服务）；每行显示 Agent 的结论：最可能的根因、置信度、是否不确定、是否还在分析 |
| Incident 详情 | Agent 的分析放在最上面：摘要、按可能性排序的原因、每条证据（引用的 id 解析成告警摘要、日志原文或服务说明，点击跳到对应告警）、建议排查、core 剔除的引用、历史分析。下面是告警和日志样本（标出 Agent 引用的）、影响范围图（出现症状的服务、Agent 认为的根因）、时间线（告警、状态变更、Agent 分析）。可以流转状态、手动请求分析 |
| 服务拓扑 | 服务目录的依赖图；只标出所选时间内（默认 1 小时）有告警的进行中 Incident，因为 Incident 不会自己关闭，全部标出来会一直是红的 |

- **刷新：** 先用轮询（列表、详情 5 秒，拓扑 10 秒；标签页不可见时暂停）。SSE 推送留到 `incident.events`（P5）。
- **类型：** `src/api/types.ts` 按接口的实际返回手写（snake_case；值为 null 的字段接口不返回，类型里是可选的）。以后 core-service 的 OpenAPI 文档稳定了，可以改成生成。
- **大模型的输出只当文本显示**（Vue 默认转义，不用 `v-html`），拓扑图提示框里的文字也先转义。
- **测试：** 不依赖页面的逻辑（证据解析、时间线、分层布局、状态流转、时间格式）用 Vitest 测；CI 另外跑 vue-tsc，模板里的类型错误也会失败。

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
| agentops | `POST /api/v1/incidents/{id}/analyses` | 请求 Agent 分析，返回 202 和待完成的分析；已有一个在等待时返回那一个（已实现） | SRE、DEVELOPER |
| | `GET /api/v1/incidents/{id}/analyses`，`GET /api/v1/analyses/{id}` | 分析结果，新的在前：状态、摘要、是否不确定、假设（组件、原因、置信度、证据）、建议、被拒收的证据（已实现） | 团队范围 |
| 实时推送 | `GET /api/v1/stream` | SSE：告警与 Incident 变更（多实例时经 Redis Pub/Sub 广播） | 登录用户 |

### 7.4 Kafka topic

由 `deploy/compose/kafka/create-topics.sh` 创建；broker 关闭了自动建 topic。所有消息值为 JSON（snake_case），schema 在 `contracts/kafka/`。

| Topic | Key | 值类型 | 分区 | 清理 | 生产者 | 消费者 |
|---|---|---|---|---|---|---|
| `logs.raw` | host | `RawLogRecord` | 6 | 保留 7 天 | ingest | stream-processor |
| `logs.normalized` | service（缺失时用 host） | `NormalizedEvent` | 6 | 3 天 | stream-processor | stream-processor（检测）、core（J5，日志检索） |
| `logs.unparsed` | source_type | `UnparsedLog` | 1 | 7 天 | stream-processor | 离线分析 |
| `rules.changelog` | rule_id | `RuleDefinition`（删除为 tombstone） | 1 | compact | core（outbox） | stream-processor |
| `topology.changelog` | service | `TopologyEntry` | 1 | compact | core（outbox） | stream-processor。不再使用（§8.4） |
| `alerts` | group_key | `Alert` | 6 | 7 天 | stream-processor | stream-processor（聚合）、core |
| `incident.candidates` | group_key | `IncidentCandidate` | 6 | 7 天 | stream-processor | core。不再使用（§8.4） |
| `incident.events` | incident_id | `IncidentEvent` | 3 | 30 天 | core（outbox） | 推送、Agent 派发（待定） |
| `agent.tasks.high` | task_id | `AgentTask` | 3 | 3 天 | core（agentops） | agent |
| `agent.tasks.low` | task_id | `AgentTask` | 3 | 3 天 | 以后的批量、巡检任务 | agent |
| `agent.results` | task_id | `AgentResult` | 3 | 7 天 | agent | core（agentops） |
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

**`TopologyEntry`**（`topology.changelog`）：`service`、`group_key`（依赖组，由 core 计算）、`depends_on`、`team`、`version`。 不再使用：关联改在 core-service（§8.4），topic 和契约保留，供以后需要时使用。

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

**`IncidentCandidate`**（`incident.candidates`）：`group_key`、`session_start`、`session_end`、`alert_ids`、`services`、`hosts`、`max_severity`、`alert_count`、`merged_from`（被合并掉的旧会话起点，供 core 合并已存在的 Incident）。 不再使用：关联改在 core-service（§8.4），topic 和契约保留，供以后需要时使用。

**`IncidentEvent`**（`incident.events`）：`event_id`、`incident_id`、`type`（`OPENED`、`ALERT_ATTACHED`、`STATUS_CHANGED`、`ASSIGNED`、`COMMENTED`、`MERGED`）、`actor_type`（`USER`、`SYSTEM`、`AGENT`）、`actor_id`、`from_status`、`to_status`、`payload`、`occurred_at`。

### 7.6 Agent 的边界（P4 第一版已实现）

1. Agent 只能通过 core-service 的**只读接口**访问数据，不连数据库，也不登录被监控的机器。现在的工具：告警与 Incident 详情、时间线、拓扑、按时间段查告警和 Incident；日志检索、指标、最近的变更随 P2、P3 加入。
2. 任务通过 `agent.tasks.high` 派发，结果写回 `agent.results`，由 core 校验后落库（`AgentTask`、`AgentResult`，schema 在 `contracts/kafka/`）。
3. 以后：每个任务携带短期、限定范围的任务 token；工具调用经过鉴权、限流，并写入审计表。identity 模块（P5）之前，core-service 只监听 127.0.0.1。

**派发（agentops 模块）：**

- Incident 1 分钟（`ops.agent.settle-after`）没有新告警，就自动派发一次分析，让 Agent 看到完整的 Incident，而不是第一条告警。之后又有新告警，再安静 1 分钟后重新分析；每个 Incident 最多自动分析 3 次。最近 1 小时之外的 Incident（例如停机一段时间后启动）只在有人请求时分析。“安静”按告警到达 core 的时间算，不按告警自身的时间。
- 同一个 Incident 同时只有一个分析在等待（部分唯一索引）；再次请求返回这一个。
- 请求和事件在同一个事务里落库（Modulith 事件登记表），事务提交后才发出任务；发送失败时重启后重发，已过截止时间的任务不再发送。
- 3 分钟（`ops.agent.timeout`）没有回答，分析记为超时；迟到的回答照样保存。

**校验（落库之前）：**

- 只接受等待中或已超时的分析的回答，重复的回答不改变已有结果。
- 每条证据都要是这个 Incident 真实有的：它的告警 id、这些告警的样本事件 id、服务目录里的名字、它自己的 id。其余的证据（编造的 id、其他 Incident 的告警）单独存入 `rejected_evidence`，不作为支持显示。
- 校验后某个假设一条证据都没有了，整个分析标为“不确定”。
- 摘要、假设、建议的数量和长度有上限。

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
- **告警抑制：** 一个问题只告警一次：同一个 `fingerprint` 持续出现时，`suppress_for` 内不再重复告警。一个 `fingerprint` 连续 `quiet-period`（默认 2 分钟）没有匹配的事件，就算这个问题已经停止；之后再出现是新的问题，即使还在上一条告警的抑制期内，也会重新告警。否则，例如抢购时一阵调用库存失败的告警之后几分钟内出现的独立库存故障，会被前一条告警的抑制挡住。间隔短于安静期时仍然算同一个持续的问题：2026-10-17 的演练里，独立库存故障在抢购结束 41 秒后开始，从日志看“调用库存失败”一直没有停过，因此并入了抢购那次告警，没有新告警（shop-design §15）。抑制期限、窗口和过期清理都用同一个检测时间（由 `detection-time` 决定是事件时间还是接收时间），日志自身的时间只作为告警里的证据。抑制状态保存在 Kafka Streams 的状态存储中，与 offset 一起提交，重启或重放时不会重复告警；过期的记录按流时间定期清理。
- **热更新：** 规则来自 `rules.changelog` 的全局表；规则变更后，新进入的事件立即按新规则匹配。
- **默认规则（从 v1 移植并扩充，见 `services/stream-processor/src/main/resources/rules/default-rules.json`）：** SSH 暴力破解（5 分钟 ≥ 5，按 host + 来源 IP）；MySQL 连接被拒绝（1 分钟 ≥ 1）；MySQL 连接数过多；MySQL 慢查询（5 分钟 ≥ 3）；应用数据库连接错误（5 分钟 ≥ 2，按 service + host）；Nginx 5xx（5 分钟 ≥ 5，按 host）；容器 OOM（≥ 1）；容器频繁重启（15 分钟 ≥ 3，按容器）；内核 OOM；磁盘使用告警（node-exporter 报告 warning 或 critical）。电商靶场（按 `event.action`）：服务连不上数据库（1 分钟 ≥ 3，按服务）；调用其他服务失败（1 分钟 ≥ 10，按服务 + 被调用方）；未处理的异常（≥ 1）；库存结算被拒、需要人工处理（≥ 1）；后台恢复步骤反复失败（5 分钟 ≥ 3）。售罄等正常业务结果不设规则。
- **样例数据的结果：** 重放 `datasets/aiops_mixed_incident_small.jsonl` 产生 7 条告警：SSH 暴力破解、MySQL 连接被拒绝、MySQL 连接数过多、payment-api 数据库连接错误、web-01 的 5xx 突增、api-gateway 容器 OOM、api-gateway 频繁重启（拓扑测试和真实 Kafka 上的端到端运行结果一致）。
- **规则来源：** J3 之前没有 core-service，stream-processor 在 `local` 配置下启动时把 `default-rules.json` 写入 `rules.changelog`；J3 起由 core-service 管理。

### 8.4 告警关联（R3 起，在 core-service 中）

**原设计与改动。** 原设计在 stream-processor 里把服务依赖图的每个连通分量算作一个依赖组，同组的告警用 5 分钟间隔的会话窗口合成一个 Incident。对电商靶场这样不成立：order 依赖 inventory 和 payment，三者又都依赖同一个 MySQL，整个靶场是一个连通分量，同时发生的两个独立故障（支付报错和库存报错）会被强行合成一个 Incident，而这正是要求分开的反例（shop-design §12.5）。改为：

- **关联放在 core-service 的 incident 模块：** 指标告警（以后经 Alertmanager）也会进 core，两类告警只在这里汇合；Incident 的状态和人工操作也在这里；告警量很小，不需要流处理。
- **主体（subject）：** 每条告警指向一个组件。调用其他服务失败的告警（规则按 `fields.dependency` 分组）指向被调用方；其他告警指向产生告警的服务（没有服务时取主机）。
- **规则：** 一条新告警加入一个活跃的 Incident（open、acknowledged、investigating、mitigated），条件是：这个 Incident 在关联间隔内有过告警（默认 15 分钟，比规则常见的 10 分钟抑制期长，持续的故障再次告警时仍在同一个 Incident 里）；并且新告警的主体与这个 Incident 的某个主体之间有依赖路径，方向不限，或者是同一个组件。**只是有共同的依赖不算相关**：payment 和 inventory 都依赖 MySQL，但它们之间没有路径。多个候选时选最近有告警的那个。
- **不自动合并、不断定根因：** 已有的 Incident 不会被自动合并；只给出提示 `shared_dependencies`，即所有主体都依赖的组件（不含主体本身），例如一次 MySQL 停机的 Incident 主体是 order-service 和 payment-service，提示 shop-mysql。
- **可靠性：** 告警模块在存储告警的事务里发布 `AlertStored`（Spring Modulith 的事件发布登记）；incident 模块在提交之后异步处理，失败或进程崩溃后重新投递，已经关联过的告警跳过；关联用 PostgreSQL 的事务级 advisory lock 串行执行，并发和多实例时都不会为相关告警各开一个 Incident。
- **拓扑来源：** catalog 模块的服务与依赖（`GET /api/v1/topology`）；local profile 启动时从 `catalog/topology.json` 载入靶场的拓扑。
- **已知局限：** 两个相关组件在间隔内先后出现独立故障，会被放进同一个 Incident；Incident 只按时间和依赖判断，不看告警内容。需要时人工处理（合并、拆分的接口待做）。

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
- **`incident_analyses`（P4）：** `id`（即任务 id）、`incident_id`、`status`（`pending`、`completed`、`failed`、`timed_out`）、`requested_by`（`system`、`user`）、`requested_at`、`deadline`、`completed_at`、`summary`、`uncertain`、`hypotheses`、`next_steps`、`rejected_evidence`（jsonb）、`model`、`tool_calls`、`duration_ms`、`error`。`incident_id` 上有 `status = 'pending'` 的部分唯一索引。

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
| 控制台（`web/`） | 5180 | `npm run dev`；`npm run preview` 用 4180。`/api` 转发到 core-service |

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

# 5. 控制台（需要 Node.js 22.12+），http://127.0.0.1:5180
cd web && npm ci && npm run dev
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

## 14. 路线图与待定事项

2026-10-17 起，开发计划统一放在这里，以 Ops-Agent 为主线；电商靶场的设计见 `shop-design.md`。原来的 J3–J5（本节旧版）和 shop-design 的 R0–R5 不再单独维护，已完成的部分记在 §14.2。

### 14.1 分层

Agent 在最上层：它只能通过第 3 层的只读工具看到第 1、2 层收集和算出的东西，不登录服务器，也不直接读靶场的数据库。

| 层 | 作用 | 现状（2026-10-17） |
|---|---|---|
| 1 采集 | 日志、指标、trace、变更记录、服务拓扑 | 日志 ✓（采集器 + ingest）、拓扑 ✓（catalog）；指标、变更记录未做；trace 只有 ID，存在日志字段里 |
| 2 确定性检测与关联 | 规则、基线异常、告警关联成 Incident | 规则 ✓、关联 ✓（§8.4）；基线异常未做 |
| 3 只读查询工具 | 告警、Incident、证据、日志检索、指标、拓扑、变更 | 告警、Incident、拓扑 ✓；日志检索、指标、变更未做 |
| 4 Agent | 调用第 3 层的工具，给出带证据引用的分析和归因 | Incident 归因第一版 ✓（§6.5、§7.6），有不用大模型的基线作对照 |

**原则：**

- **先确定性，后智能。** 检测和关联由可测试、可解释的规则与算法完成；Agent 做 Incident 层面的分析，不参与逐条日志的检测（成本和稳定性都不允许）。关闭 Agent，平台仍能检测、告警和关联。
- **Agent 的上限由数据和工具决定。** 先补齐它需要的数据：可检索的日志、指标、变更记录。
- **识别症状，而不是看到注入。** 演练控制台的故障注入和攻击只记作标准答案，不进入 Ops-Agent 的任何输入；发布、改配置这类变更在现实中本来就有记录，可以作为正常输入。
- **输出可验证。** Agent 的结论都要引用具体的告警、日志或指标，证据不足时回答“不确定”；先只给建议，不执行操作。
- **数字有出处。** 检出率、误报、延迟、归因准确率都来自演练记录和固定资源条件下的实验（shop-design §11.5–§11.6）。

### 14.2 已完成

| 阶段 | 内容 |
|---|---|
| J0–J2 | 工程骨架、CI；ingest-service；stream-processor 的解析、脱敏、规则检测、去重 |
| 靶场 S0–S5 | 订单、库存、支付三个服务，请求生成工具，故障注入和 9 个场景（shop-design） |
| R0 | 修正与复验：连接嵌套、故障残留、端口绑定、重复事件计数、抑制时间、JSON 脱敏、开发密钥 |
| R1 | 抢购基线：单商品模式、逐单核对、积压指标、固定资源下的性能实验 |
| R2 | 靶场 JSON 日志接入（采集器、ECS 解析、事件名、靶场规则）；core-service 的告警模块（落库、查询、证据） |
| R3（关联） | catalog 模块；incident 模块：按依赖路径关联（§8.4）、生命周期、时间线；反例场景 `independent-faults` |
| P1 第一版 | 演练控制台（shop-design §15）：流量、抢购、9 个故障场景、标准答案、顾客视角的评测 |
| P4 第一版 | Agent 契约；core 的 agentops 模块（派发、证据校验、查询）；Python Agent（工具调用循环、基线对照）；控制台展示分析并给归因打分 |
| P5 界面第一版 | Ops-Agent 控制台（§6.6）：Incident 列表、详情（Agent 的结论与证据、告警与日志样本、影响范围、时间线、状态流转、手动请求分析）、服务拓扑 |

### 14.3 接下来

| 阶段 | 内容 | 验收 |
|---|---|---|
| **P1 演练控制台与评测** | 本机网页（shop-design §15）：持续的正常流量和抢购；一键注入、清除故障；简单的攻击；标准答案时间线。按标准答案自动给 Ops-Agent 打分。故障和攻击的种类逐步增加：先接入已有的 9 个场景，再加资源类故障（CPU、内存、线程池）、进程崩溃、坏的发布，以及攻击（登录暴力破解、请求洪水、SQL 注入特征、绕过订单服务调用内部接口） | 控制台上的每个操作都有标准答案记录；评测按每次演练给出：是否检出、首条告警延迟、Incident 数量和划分是否正确、正常时段的误报 |
| **P2 补齐确定性底座** | 日志存储与检索（D4）；Prometheus + Alertmanager（连接池、延迟、积压等指标告警进入 core，带触发和恢复）；变更记录（发布、配置变更）；新故障和攻击的规则；无规则的基线异常检测（日志模板 + 频率基线），与规则检测在同一套演练上对比 | 新故障和攻击都能被检出；基线检测与规则检测的检出率、误报、延迟对比有记录 |
| **P3 只读工具 API** | 给 Agent 和 Ops-Agent 界面用：检索日志（按服务、时间、trace ID）、Incident 及其证据、指标查询、拓扑、最近的变更 | OpenAPI 文档；每个工具有集成测试；只读 |
| **P4 Agent** | 第一版已完成（§14.2）。接下来：在同一组演练上对比大模型与基线的归因；工具随 P2、P3 增加（日志检索、指标、变更）；工具调用审计 | 在演练上评估归因：正确、部分正确、错误、不确定各占多少；每条结论都能追溯到证据 |
| **P5 加固** | identity（JWT，对外部署前必须完成）；规则管理移入 core（版本、经 Outbox 发布到 `rules.changelog`）；`incident.events`；Ops-Agent 界面的其余部分（告警与规则页面、登录，第一版见 §14.2） | 未认证请求被拒；规则变更不重启生效 |

### 14.4 待定事项

| 编号 | 事项 | 影响 | 状态 |
|---|---|---|---|
| D1 | 持久层：MyBatis-Plus 或 Spring Data JPA | core-service | **已定：MyBatis-Plus**（2026-10-17） |
| D2 | Agent 的功能范围 | P3 的工具、P4 | 先做 Incident 归因分析（2026-10-17）；问答、巡检以后再定 |
| D3 | 被监控的业务 | 数据集、规则、评测 | **已定：电商靶场**，保留 MySQL（2026-10-17）；是否接入真实服务器日志作为第二个环境，以后再定 |
| D4 | 日志存储与检索：PostgreSQL（按天分区、全文检索）还是 OpenSearch | P2、P3 | P2 开工前确定 |
| D5 | Agent 的实现语言：Python 还是 Java（Spring AI） | P4 | **已定：Python**（2026-10-17），经 Kafka 和只读 HTTP 接口与 Java 端交互 |

---

## 15. 与前期分析文档的差异

| 项 | 分析文档 | 本文 | 原因 |
|---|---|---|---|
| 仓库 | 在 AIOps 仓库内重组为 monorepo | 新仓库 Ops-Agent | 与 v1 代码分开，历史更清晰 |
| 告警去重 | Redis `SET NX EX` | Kafka Streams 状态存储 | 与 offset 一起提交，重启和重放时不重复告警；不需要在流处理中访问外部系统 |
| 窗口大小 | 规则任意配置 | 1 / 5 / 15 分钟三档 | Kafka Streams 窗口大小在构建拓扑时固定，热更新规则时只能在固定档位间切换 |
| 接入请求格式 | 未规定 | 与 v1 数据集格式一致 | 数据集可以直接重放 |
| 字段命名 | 未规定 | HTTP 与 Kafka 统一 snake_case | Python 侧直接使用，无需转换 |
