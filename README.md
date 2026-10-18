# Ops-Agent

面向微服务系统值班运维的平台：日志接入 → 实时检测 → 按服务依赖把告警合并为 Incident → Agent 带证据地分析根因。仓库里还有一个电商靶场和演练控制台，用来制造流量和故障、记下标准答案、给 Ops-Agent 打分。

- **Java（Spring Boot 4、Kafka Streams、PostgreSQL、Redis）：** 接入、解析与脱敏、规则检测、告警关联成 Incident、派发分析任务、校验 Agent 的证据，以及系统数据的唯一入口。
- **Python Agent：** 从 Kafka 取分析任务，让大模型通过 core-service 的只读接口查看 Incident，提交带证据引用的归因；另有不用大模型的基线作对照。
- **控制台（Vue 3 + TypeScript）：** Incident 列表和详情（Agent 的结论与证据、告警和日志样本、影响范围、时间线）、服务拓扑。

```
 shop (order, inventory, payment) --JSON logs--> log-shipper --HTTP--> ingest-service
                                                                            | Kafka logs.raw
                                                                            v
                                                     stream-processor: parse, redact, rules, dedup
                                                                            | Kafka alerts
                                                                            v
 web console (Vue 3) <--REST-- core-service: store alerts, correlate into incidents, dispatch analyses,
                                     ^        check the agent's evidence
                     read-only HTTP  |        | Kafka agent.tasks / agent.results
                                     +-- agent (Python): language model, or the baseline

 lab: traffic and faults on the shop, ground truth, scoring against what core-service saw (Ops-Agent never sees it)
```

设计文档：[docs/project-design.md](docs/project-design.md)（Ops-Agent）、[docs/shop-design.md](docs/shop-design.md)（靶场与演练）。

## 一键演示

需要 JDK 21+、Docker（Windows 上是 Docker Desktop，先启动它）、Node.js 22.12+、Python 3.11+。在仓库根目录运行：

```bash
java tools/Demo.java              # 启动容器、构建、启动全部服务、Agent 和控制台；Ctrl+C 停止
```

- 它依次启动 Kafka、PostgreSQL、Redis 和靶场的 MySQL、Redis（Docker Compose），构建 jar，启动 Ops-Agent 的三个服务、靶场的三个服务和演练控制台，最后是日志采集器、Agent 和 Ops-Agent 控制台。缺少的 npm 包和 Agent 的虚拟环境（`agent/.venv`）会自动安装。
- 第一次运行要下载镜像和依赖，需要几分钟；之后约 2 分钟，加 `--skip-build` 不重新构建时约 1 分钟。
- **Agent 的模式：** 有大模型配置（`agent/.env` 或 `OPS_AGENT_LLM_*` 环境变量，见 [agent/README.md](agent/README.md)）时用大模型，没有就自动用基线模式；`--baseline` 强制用基线。
- 每个进程的输出在 `logs/demo/<名字>.log`。Ctrl+C 只停进程，容器继续运行；`java tools/Demo.java stop` 把容器也停掉（数据保留），启动脚本被强制结束时它也会清理留下的进程。
- 演示时故障之间只隔几分钟，所以关联间隔设为 3 分钟（默认 15 分钟），每次故障各自成为一个 Incident。

**演示流程：**

1. 打开演练控制台 http://127.0.0.1:8090，开启正常流量（每秒几单）。
2. 注入一个故障，例如 `mysql-down` 30 秒：停掉靶场的 MySQL。
3. 打开 Ops-Agent 控制台 http://127.0.0.1:5180：几秒内出现 Incident（实测 1.3 秒），详情页有告警、日志样本、影响范围和时间线。
4. 最后一条告警之后约 1 分钟，Agent 的分析出现：最可能的根因和置信度、引用的证据（点击跳到对应的告警和日志样本）、排查建议。core 校验不过的引用单独列出。
5. 回到演练控制台看评测：是否检出、首条告警的延迟、Incident 划分是否正确、归因是否正确。

在开发服务器上运行时，本机用 `ssh -L 5180:127.0.0.1:5180 -L 8090:127.0.0.1:8090 <服务器>` 转发后打开这两个页面。

## 当前进度

| 部分 | 状态 |
|---|---|
| 接入与检测（ingest-service、stream-processor） | 完成：HTTP 接入与限流、解析与脱敏、规则检测、去重与抑制 |
| core-service | 告警落库与查询；按依赖路径把告警关联成 Incident，生命周期与时间线；派发分析任务、校验 Agent 的证据 |
| Agent（Python） | Incident 归因第一版：工具调用循环、证据自查、不用大模型的基线 |
| Ops-Agent 控制台（web） | 第一版：Incident 列表与详情、服务拓扑 |
| 电商靶场与演练控制台 | 订单、库存、支付三个服务；秒杀（Redis + Lua 判断资格，Redis Stream 异步下单）；9 个故障场景；标准答案、顾客视角的评测、归因打分 |
| 接下来 | 关联提示会误导的故障场景（比较大模型和基线）；日志检索与指标（P2）；认证与规则管理（P5） |

各阶段的内容和验收见设计文档 §14，演练结果见 shop-design §15。

## 分别启动各部分

调试某个服务时，可以不用上面的启动脚本，在 IDEA 里或用 Gradle 单独启动。需要 Docker（Windows 上为 Docker Desktop + WSL2）。JDK 21 由 Gradle 自动下载，也可以自行安装。

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
./gradlew :services:core-service:bootRun         # 告警、Incident、Agent 分析，http://localhost:8080/swagger-ui.html
java tools/Replay.java datasets/aiops_mixed_incident_small.jsonl
```

开发用的 API Key 是 `dev-ingest-key`，只在 `local` profile 下有效（不指定 profile 时默认就是 `local`）。其他环境必须配置自己的 `ops.ingest.sources`，仍用开发密钥或没有配置来源时，服务拒绝启动。所有服务默认只监听 127.0.0.1；ingest-service 要接收别的机器发来的日志时，设置 `SERVER_ADDRESS` 并配置自己的 `ops.ingest.sources`。

查看结果：`curl localhost:8080/api/v1/alerts` 列出告警（带触发事件作为证据）；或者 `docker compose --profile tools up -d` 后打开 Kafka UI（http://localhost:8089），看 `logs.normalized`、`alerts` 等 topic。样例数据会产生 7 条告警。

## Ops-Agent 控制台（web）

Incident 列表、Incident 详情（Agent 的结论与证据、告警和日志样本、影响范围、时间线）、服务拓扑。需要 Node.js 22.12+，core-service 先启动：

```bash
cd web && npm ci && npm run dev     # http://127.0.0.1:5180，/api 转发到 core-service
```

说明见 [web/README.md](web/README.md)。Agent 的安装、配置和单独运行见 [agent/README.md](agent/README.md)。

## 电商靶场（shop）

给 Ops-Agent 监控用的最小电商系统：订单、库存、支付模拟三个服务。它们产生结构化日志、跨服务 trace 和指标，可以注入超时与故障。设计见 [docs/shop-design.md](docs/shop-design.md)；演练控制台 `./gradlew :shop:lab:bootRun`，http://localhost:8090（shop-design §15）。

```bash
cd deploy/shop && docker compose up -d                  # MySQL 8.4 和秒杀用的 Redis（与 Ops-Agent 的基础设施分开）
./gradlew :shop:inventory-service:bootRun              # 8092
./gradlew :shop:payment-service:bootRun                # 8093
./gradlew :shop:order-service:bootRun                  # 8091
./gradlew :shop:load-generator:run --args="--rate 10 --duration 30s"
```

- 也可以在 IDEA 里直接运行三个服务的主类。
- 请求生成工具也可以先用 `./gradlew :shop:load-generator:installDist` 安装，再运行 `shop/load-generator/build/install/load-generator/bin/load-generator`（Windows 上运行同目录下的 `.bat`）。
- 运行结束时，请求生成工具核对重复请求、库存账目和资金（已付款的订单都扣了款，取消的都没扣），有任何不一致就以非 0 退出。参数见 `--help`。
- 故障场景：`--scenario payment-timeout` 等 8 个，见设计文档 §10。管理端口上的 `/actuator/faults` 可以手动注入延迟和错误。
- 秒杀：`--seckill SKU-2001 --stock 100 --rate 200 --duration 10s`。资格判断和一人一单在 Redis 里用一个 Lua 脚本完成，受理的订单进 Redis Stream，由订单服务的消费者组异步下单；结束时核对库存、资金，并对账 Redis 与 MySQL。设计和与普通下单的对比实验见设计文档 §16。
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
deploy/shop/      靶场的 MySQL 和 Redis
datasets/         样例日志
tools/            一键演示（Demo.java）、重放工具、日志采集器（log-shipper）
docs/             设计文档
```
