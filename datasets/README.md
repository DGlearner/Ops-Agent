# 样例数据

| 文件 | 内容 |
|---|---|
| `aiops_mixed_incident_small.jsonl` | 100 条混合日志（nginx、linux、mysql、app_service、docker），包含一次 MySQL 不可用引起的级联故障（MySQL 连接被拒绝 → 应用数据库连接错误 → Nginx 5xx）、一次 SSH 暴力破解和两次容器 OOM。由 AIOps v1 的 `generate_aiops_logs.py` 生成，IP 地址均为文档保留网段。 |

每一行都是一个 `POST /api/v1/logs` 请求体，可以直接重放（见 `tools/`）。
