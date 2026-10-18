# Ops-Agent

面向微服务系统值班运维的平台：日志接入 → 实时检测 → 按服务依赖合并告警为 Incident → Agent 辅助诊断与问答。

- **Java（Spring Boot 4、Kafka Streams、PostgreSQL、Redis）：** 接入、解析、检测、聚合、Incident、权限，以及系统数据的唯一入口。
- **Python Agent：** 方案待定，只通过 Java 提供的只读工具访问数据。

设计文档：[docs/project-design.md](docs/project-design.md)

## 当前进度

| 阶段 | 状态 |
|---|---|
| J0 工程骨架 | 完成 |
| J1 接入（ingest-service） | 完成 |
| J2 解析与检测（stream-processor） | 完成（告警聚合留到 J4） |
| J3 core 基础 · J4 告警与 Incident · J5 加固 | 未开始 |

## 本地运行

需要 Docker（Windows 上为 Docker Desktop + WSL2）。JDK 21 由 Gradle 自动下载，也可以自行安装。

```bash
cd deploy/compose
docker compose up -d                       # Kafka、PostgreSQL（pgvector）、Redis，并创建 topic
docker compose --profile tools up -d       # 另外启动 Kafka UI：http://localhost:8089
```

端口只绑定在 127.0.0.1。如需修改，复制 `deploy/compose/.env.example` 为 `.env`。

构建、运行与重放（Windows 上用 `gradlew.bat`）：

```bash
./gradlew build                                   # 编译并运行全部测试（集成测试需要 Docker）
./gradlew :services:ingest-service:bootRun        # 日志接入，http://localhost:8081/swagger-ui.html
./gradlew :services:stream-processor:bootRun     # 解析与检测，启动时写入默认规则
./gradlew :services:core-service:bootRun         # 告警落库与查询，http://localhost:8080/swagger-ui.html
java tools/Replay.java datasets/aiops_mixed_incident_small.jsonl
```

开发用的 API Key 是 `dev-ingest-key`，只在 `local` profile 下有效（不指定 profile 时默认就是 `local`）。其他环境必须配置自己的 `ops.ingest.sources`，仍用开发密钥或没有配置来源时，服务拒绝启动。

查看结果：`curl localhost:8080/api/v1/alerts` 列出告警（带触发事件作为证据）；或者 `docker compose --profile tools up -d` 后打开 Kafka UI（http://localhost:8089），看 `logs.normalized`、`alerts` 等 topic。样例数据会产生 7 条告警。

## Ops-Agent 控制台（web）

Incident 列表、Incident 详情（Agent 的结论与证据、告警和日志样本、影响范围、时间线）、服务拓扑。需要 Node.js 22.12+，core-service 先启动：

```bash
cd web && npm ci && npm run dev     # http://127.0.0.1:5180，/api 转发到 core-service
```

说明见 [web/README.md](web/README.md)。

## 电商靶场（shop）

给 Ops-Agent 监控用的最小电商系统：订单、库存、支付模拟三个服务。它们产生结构化日志、跨服务 trace 和指标，S5 起可以注入超时与故障。设计见 [docs/shop-design.md](docs/shop-design.md)。

| 阶段 | 状态 |
|---|---|
| S0 设计、MySQL、公共模块 · S1 库存 · S2 支付 · S3 订单 · S4 请求生成工具 · S5 故障注入与恢复 | 完成 |

```bash
cd deploy/shop && docker compose up -d                  # MySQL 8.4（与 Ops-Agent 的基础设施分开）
./gradlew :shop:inventory-service:bootRun              # 8092
./gradlew :shop:payment-service:bootRun                # 8093
./gradlew :shop:order-service:bootRun                  # 8091
./gradlew :shop:load-generator:run --args="--rate 10 --duration 30s"
```

- 也可以在 IDEA 里直接运行三个服务的主类。
- 请求生成工具也可以先用 `./gradlew :shop:load-generator:installDist` 安装，再运行 `shop/load-generator/build/install/load-generator/bin/load-generator`（Windows 上运行同目录下的 `.bat`）。
- 运行结束时，请求生成工具核对重复请求、库存账目和资金（已付款的订单都扣了款，取消的都没扣），有任何不一致就以非 0 退出。参数见 `--help`。
- 故障场景：`--scenario payment-timeout` 等 8 个，见设计文档 §10。管理端口上的 `/actuator/faults` 可以手动注入延迟和错误。
- 日志：`logs/shop/<服务名>.log`，每行一条 ECS JSON，带 `trace.id`。用 `tools/log-shipper` 送进 Ops-Agent 检测，见 [docs/shop-design.md](docs/shop-design.md) §12.2、§14。
- 指标：`http://localhost:819x/actuator/prometheus`，管理端口只监听 127.0.0.1。

下单示例：

```bash
curl -s localhost:8091/api/v1/orders -H 'Content-Type: application/json' -H 'Idempotency-Key: demo-1' \
  -d '{"user_id":"u-1001","items":[{"sku":"SKU-1001","quantity":2}]}'
```

## 目录

```
build-logic/      Gradle convention plugins
libs/             共享库（消息契约）
services/         ingest-service、stream-processor、core-service
shop/             电商靶场：order、inventory、payment 服务，shop-common，load-generator
contracts/        Kafka 消息的 JSON Schema
agent/            Python Agent：Incident 归因（agent/README.md）
web/              Ops-Agent 控制台：Vue 3 + TypeScript（web/README.md）
deploy/compose/   本地基础设施
deploy/shop/      靶场的 MySQL
datasets/         样例日志
tools/            重放工具等开发脚本
docs/             设计文档
```
