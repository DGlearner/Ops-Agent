# Ops-Agent

面向微服务系统值班运维的平台：日志接入 → 实时检测 → 按服务依赖合并告警为 Incident → Agent 辅助诊断与问答。

- **Java（Spring Boot 4、Kafka Streams、PostgreSQL、Redis）：** 接入、解析、检测、聚合、Incident、权限，以及系统数据的唯一入口。
- **Python Agent：** 方案待定，只通过 Java 提供的只读工具访问数据。

设计文档：[docs/project-design.md](docs/project-design.md)

## 当前进度

| 阶段 | 状态 |
|---|---|
| J0 工程骨架 | 进行中 |
| J1 接入 · J2 解析与检测 · J3 core 基础 · J4 告警与 Incident · J5 加固 | 未开始 |

## 本地运行

需要 Docker（Windows 上为 Docker Desktop + WSL2）。JDK 25 由 Gradle 自动下载，也可以自行安装。

```bash
cd deploy/compose
docker compose up -d                       # Kafka、PostgreSQL（pgvector）、Redis，并创建 topic
docker compose --profile tools up -d       # 另外启动 Kafka UI：http://localhost:8089
```

端口只绑定在 127.0.0.1。如需修改，复制 `deploy/compose/.env.example` 为 `.env`。

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
