# Ops-Agent 优化设计

更新日期：2026-11-21

## 目标

在现有日志接入、实时检测、Incident 关联和证据型 Agent 的基础上，提升系统的可观测性、数据安全、恢复能力和评测可信度。优化按阶段推进，每阶段都必须有代码、测试和可核对的验收结果。

## 当前判断

项目已经具备完整闭环，包括 HTTP 日志接入、Kafka 投递和幂等事件 ID，raw 日志解析、脱敏、规则检测、baseline 检测和告警抑制，core-service 的告警落库、拓扑关联、Incident 生命周期和 Agent 任务，Agent 的证据引用校验、对话式值班助手、操作确认和 SSE 轨迹，以及可注入故障的电商靶场和固定问题评测。

主要缺口集中在运行观测、隐私字段覆盖、Kafka 恢复演练、规则治理和 Agent 评测独立性。

## 阶段一：日志安全和检测观测

本阶段立即执行。

### 扩大脱敏覆盖

保留现有凭据脱敏，新增常见日志隐私字段，包括邮箱地址、手机号和国际手机号的常见格式、银行卡号、卡号字段、CVV 字段，以及 `email`、`phone`、`mobile`、`card_no`、`card_number`、`cvv` 等字段名。

JSON 和嵌套字段按字段名递归处理，自由文本按模式处理。service、host、trace ID、event type、订单故障类型继续保留。raw topic 仍可能包含采集原文，必须依靠 topic 权限、加密和保留策略保护。

验收标准是单元测试覆盖支付、登录、嵌套 ECS 字段、长文本和未闭合引号，normalized 和 unparsed 输出都不能泄露新增样例中的隐私值。

### 增加解析指标

新增 Micrometer 指标 `ops.stream.parse`，结果标签使用 `parsed`、`unparsed`、`error`。新增 `ops.stream.redaction`，结果标签使用 `parsed_fields`、`unparsed_message`。

指标只使用有限枚举标签，禁止把 service、host、event message 等高基数值放进标签。

验收标准是解析成功、解析失败和 parser exception 各有测试，指标可从 stream-processor 的 Prometheus endpoint 查询，并且不改变原有解析输出和规则检测行为。

## 阶段二：运行恢复和 Kafka 观测

本阶段在阶段一通过后执行。

### Kafka Streams 恢复演练

覆盖 stream-processor 重启、state store restore、repartition topic 积压、重复输入和迟到事件、规则全局表恢复，以及关闭旧实例后使用相同 application ID 启动新拓扑。

验收标准是同一事件不重复产生告警，状态恢复后窗口、去重和抑制继续工作，并记录 restore 时间、consumer lag 和告警延迟。

### 运行指标

补充 Kafka consumer lag、raw 到 parsed 的处理延迟、raw 到首条告警的端到端延迟、每条规则命中数和抑制数、解析失败原因、Incident 关联成功和重试次数、Agent 分析成功、超时、降级和补查次数。

所有标签使用低基数枚举。高基数对象写日志或 trace，不写指标标签。

## 阶段三：治理和评测

### 规则治理

增加规则版本、发布人、变更原因、审批状态、回滚记录和命中效果对比。

### 访问控制

为日志源、Incident、Agent 证据和操作卡片补充用户、团队和权限边界。raw 日志禁止 Agent 直接读取。

### Agent 评测

保留现有 20 问、120 次真实靶场评测，继续区分初始证据齐备、无额外取证且回答正确、语义正确、HTTP/SSE 首字延迟、降级、超时和供应商异常。

后续增加独立人工双评、冷缓存对照、并发压力和多模型对照。共享少量 Incident 的结果只能作为开发样本，不能写成生产 SLA。

## 迁移和发布

每个阶段独立提交并运行对应测试。Kafka Streams 拓扑变更使用同一个 application ID 恢复原有 state stores 和 repartition topic，禁止新旧两个 application 同时写入 alerts。升级前保留 logs.raw、logs.normalized、state store changelog 和明确的消费 offset。如果无法恢复旧状态，必须在维护窗口从 raw offset 重放，并隔离旧告警输出。

不在本地优化中修改生产环境配置、删除历史数据或绕过鉴权。

## 阶段一验收结果

已实现：

- `services/stream-processor/.../Redactor.java` 新增邮箱、手机号、银行卡号、CVV 以及对应结构化字段名的脱敏。
- `ParserRegistry` 支持 Micrometer 解析指标，使用低基数 `result` 标签记录 parsed、unparsed、error，并记录脱敏输出分支。
- `StreamConfiguration` 将 MeterRegistry 注入解析器，stream-processor 继续暴露 Prometheus endpoint。
- `RedactorTest` 新增支付、登录、手机号和嵌套结构化字段测试，`ParsersTest` 新增解析指标测试。

验证命令：

```powershell
./.gradle/gradle-9.8.1/bin/gradle.bat -g .gradle/user-home :services:stream-processor:test --tests '*RedactorTest' --tests '*ParsersTest' --no-daemon
```

结果：57 个脱敏和解析测试通过。Docker 集成测试属于阶段二，当前没有在阶段一重复执行。

仍待执行：Kafka Streams 重启和 state restore 演练、consumer lag 和端到端延迟指标、规则治理、认证和独立人工双评。
