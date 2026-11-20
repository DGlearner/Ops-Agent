# mysql-down 无告警：启动链路修复与验证

## 已确认的问题

2026-11-16 的本地演练中，采集器在 `logs/shop` 尚不存在时退出：

```text
NoSuchFileException: logs\shop\.log-shipper-state.json.tmp
```

`LogShipper.files()` 能接受尚不存在的输入目录，但启动时立即保存断点；`ShipperState.save()` 没有创建状态文件的父目录，因而在开始轮询前退出。之后即使服务写出日志，也没有采集器将它们送到 ingest。

另一个独立问题是流量启动需要先读取商品目录。MySQL 已停止时，该请求返回 503，流量并未启动；原实现还会先停止已有流量，再尝试读取目录。没有业务请求会减少故障日志，不能仅凭“故障注入成功”判断检测链路已经获得样本。

曾观察到的“日志文件为 0 字节”不足以证明共享配置加载顺序有问题。本次在独立端口、独立日志目录运行原服务包，等待健康后读到了 ECS JSON；原包内的共享配置和处理器也与源码一致。随后三项服务移除临时 `--logging.file.name` / `--logging.structured.format.file` 参数，仍正常写日志并产生实时告警。因此保留现有 `ShopDefaultsEnvironmentPostProcessor`，不把未复现的假设作为根因。

## 修改

- `ShipperState.save()` 创建状态文件父目录，包括自定义 `--state` 路径；继续使用临时文件和原子替换，断点语义不变。
- Demo 创建 `logs/shop`，并在商店健康后读取每个服务当前 PID 写出的 ECS 启动日志，再启动采集器。旧文件或只通过 HTTP 健康检查不再足以放行启动。
- Lab 先完成商品目录预检，再替换流量。预检失败返回带操作指引的 Problem Details 503，原有流量继续运行；页面提示先启动流量，再注入 mysql-down。

## 验证

JDK 21 / Gradle 9.8.1：shipper 8 项、shop-common 14 项、Lab 13 项，共 35 项测试通过，Demo 编译通过。

新增回归覆盖输入目录晚于采集器出现、自定义状态目录创建和断点续传、故障期启动失败不伪报成功、调整失败保留正在运行的流量，以及目录恢复后能够替换流量。HTTP 测试核对 503、Problem Details 类型和可操作提示。已有 shop-common 测试读取真实日志文件并检查 ECS、trace id 和数据库故障事件。

2026-11-16 的第二次有限演练使用正常 JVM 参数，保留 `-Dshop.log-dir`，没有临时日志覆盖参数；采集器使用原断点，不加 `--from-start`。先启动每秒 3 单并确认已有 9 单，再注入 30 秒 mysql-down：

| 项目 | 记录（UTC） |
| --- | --- |
| Episode | `6a632f08-359b-4626-ba37-4432e3326077` |
| 注入开始 | `15:27:10.308210600Z` |
| 新 Incident | `01a1e4f8-2fce-758f-acd3-76ee06a96d3f` |
| Incident 打开 | `15:27:12.725731Z` |
| 自动清除完成 | `15:27:44.439265600Z`，`actions_ok=true` |

该 Incident 最终有 9 条告警，涉及 order-service、payment-service、inventory-service，共同依赖为 shop-mysql。Incident 打开时间比 Episode 开始晚约 2.4 秒，脚本首次观察到新 Incident 晚约 5.6 秒；这是本次运行记录，不是性能基准。故障期间调整流量得到预期 503，原运行的开始时间、速率和运行状态保留。恢复后三项管理健康端点均为 UP，测试流量已停止。采集器记录无拒收、无重试。

完整本地证据位于忽略的 `runs/mysql-down-fixed-evidence.json`，包括请求结果、Episode、前后 Incident 和清理状态；日志位于 `logs/demo/*.fixed.*.log`。这些文件不包含模型密钥。

## 模型与证据边界

正常启动验证产生的新 Incident 也已自动分析完成：任务 `01a1e4f9-ccbf-7207-864a-a88365b603fc`，`15:28:58.559637Z` 请求、`15:29:16.369495Z` 完成，模型 gpt-6-luna，报告耗时 17,797 ms、8 次工具调用。结论为共同依赖 shop-mysql 连接中断或不可用，置信度 high，`rejected_evidence=[]`。该记录已经写入 `runs/mysql-down-fixed-evidence.json`；从最后告警 `15:27:49.528460Z` 到自动请求约 69 秒。

助手对新 Incident 的回答也复用了上述 analysis id，`status=completed`、`stale=false`，回答耗时 10,234 ms、首段回答 2,282 ms、`actions=0`。它没有从“暂无新告警”推断服务已经恢复。

前一次恢复链路的 Incident `01a1e4e7-f3eb-7021-ad91-a389ff94d56c` 已由真实 gpt-6-luna 自动分析完成：任务 `01a1e4ea-72a4-7e5d-8857-aae8c65ca825`，`15:12:12.452788Z` 请求、`15:12:34.969461Z` 完成，报告耗时 22,438 ms、8 次工具调用，根因为 shop-mysql 不可用或连接中断，置信度 high。

值班助手复用了该已完成分析，SSE 报告同一 analysis id、`stale=false`，回答完成且未生成操作卡片；引用校验全部有出处。回答保留了“缺少数据库自身日志、无法确认具体停机机制”的限制。该对话用时 14,577 ms，首段回答 5,842 ms，仅代表此次调用。证据在忽略的 `runs/mysql-down-diagnosis-evidence.json`。

前一次采集器曾用 `--from-start` 补采，19 条告警包含历史样本，不能全部归于单次 30 秒故障。此前助手的容器探针还收到 Docker 权限错误，它明确报告无法读取状态，没有据此断言容器已经停止。Lab 本次以具备 Docker 访问权限的进程运行，注入和自动恢复均成功；随后仅重启 core，使其具备同样的 Docker 访问权限，实际调用 `GET /api/v1/lab/containers/shop-mysql` 返回 `{"name":"shop-mysql","state":"running"}`，记录在本次证据文件中。

另一个待改进项：追问“复查容器当前状态”会被路由为 `ops_query`，该路径查询服务日志，没有调用已恢复的容器探针。即使问题带 Incident 编号和 Runbook 要求，包含状态查询语义时仍可能走此路径。本次保留了该追问的 SSE 与回答作为后续路由优化证据；助手如实说明无法由空日志确认恢复。不能把容器 API 已恢复等同于这些状态追问已能使用它。本次未修改这项独立的对话路由行为。
