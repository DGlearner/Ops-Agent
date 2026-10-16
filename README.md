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
java tools/Replay.java datasets/aiops_mixed_incident_small.jsonl
```

开发用的 API Key 是 `dev-ingest-key`（只用于本地）。

查看结果：`docker compose --profile tools up -d` 后打开 Kafka UI（http://localhost:8089），看 `logs.normalized`、`alerts` 等 topic。样例数据会产生 7 条告警。

## 目录

```
build-logic/      Gradle convention plugins
libs/             共享库（消息契约）
services/         ingest-service、stream-processor、core-service
contracts/        Kafka 消息的 JSON Schema
agent/            Python Agent（待定）
deploy/compose/   本地基础设施
datasets/         样例日志
tools/            重放工具等开发脚本
docs/             设计文档
```
