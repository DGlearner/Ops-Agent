# 电商靶场（shop）设计

版本 v0.1 · 2026-10-16

靶场是一个给 Ops-Agent 监控的最小电商系统，由订单、库存、支付模拟三个 Java 服务组成。它产生真实的日志、指标和跨服务 trace，并且可以按需注入超时和故障，让 Ops-Agent 有可以检测、可以评估的对象。

---

## 1. 范围

**要做：**

- 订单、库存、支付模拟三个服务，跑通正常交易流程。
- 库存不超卖、不泄漏；重复请求不重复下单、不重复扣款、不重复扣库存。
- 结构化日志、跨服务 trace_id、Actuator/Micrometer 指标。
- 请求生成工具：持续下单、制造重复请求，结束时核对库存账目。
- 随后（S5）加入可控的超时与失败场景，并记录每次注入的起止时间，作为 Ops-Agent 评估的标准答案。

**不做：** 商城前端、优惠券和秒杀等营销功能、用户注册登录（`user_id` 由请求直接传入）、物流、退款、异常分析（这是 Ops-Agent 的事）。

**与 Ops-Agent 的关系：** 靶场是独立系统，不依赖 Ops-Agent 的代码、数据库或 Kafka。两者只共用一个 Gradle 工程，方便在 IDEA 里一次打开。把靶场日志送进 Ops-Agent 的采集方式不在本文范围内。

---

## 2. 技术选择

| 领域 | 选择 | 理由 |
|---|---|---|
| 语言与框架 | Java 21、Spring Boot 4.1.1 | 与 Ops-Agent 一致 |
| 数据库 | MySQL 8.4 LTS（8.4.10），三个服务各自一个库和账号 | 国内电商主流。Ops-Agent 已有 MySQL 日志规则（连接被拒、连接数满），靶场能产生真实的 MySQL 日志。与 Ops-Agent 的 PostgreSQL 分开，数据库故障不会波及监控系统本身 |
| 持久层 | Spring `JdbcClient` + 手写 SQL，Flyway 管理迁移 | 库存正确性靠 `WHERE available >= ?` 这类条件更新，显式 SQL 最直观；也不影响 Ops-Agent core-service 的持久层选择（D1） |
| 服务间调用 | 同步 HTTP（`RestClient`），每个下游单独设置连接超时和读超时 | 下单需要立即知道库存和支付结果 |
| 线程模型 | 平台线程（Tomcat 默认线程池），不开虚拟线程 | 贴近企业现状；“下游变慢 → 线程池占满 → 级联失败”这类典型故障才能复现 |
| Trace | Micrometer Tracing + OpenTelemetry bridge，W3C `traceparent` 头 | 只用于跨服务关联日志，不部署 Zipkin/Jaeger |
| 指标 | Actuator + Micrometer，`/actuator/prometheus` | 不部署 Prometheus；Ops-Agent 以后直接抓取 |
| 日志 | 控制台为人读格式；文件为 JSON（ECS 格式） | 人看控制台，采集器读文件 |
| Redis | **不用** | 幂等和库存都以 MySQL 的唯一约束和条件更新为准；没有热点缓存、限流或分布式锁的需要 |
| Kafka | **不用** | 流程是同步编排；确认、释放库存的可靠执行用订单库里的待办表加重试即可（§7.5）；没有需要异步分发的下游。以后加入“支付异步回调”或订单事件的下游（物流、通知）时再引入 |

---

## 3. 架构

```
load-generator ──HTTP──▶ order-service ──HTTP──▶ inventory-service ──▶ MySQL: shop_inventory
                              │        └──HTTP──▶ payment-service   ──▶ MySQL: shop_payment
                              └──▶ MySQL: shop_order
```

- **订单服务是唯一的流程编排者**（Saga 编排）。库存和支付只执行命令，从不自行决定订单结局。所以不存在“库存自动过期释放”和“支付成功”互相竞争的情况。
- 每个服务只访问自己的库。三个库在同一个 MySQL 实例里，但账号互相隔离。

| 服务 | 业务端口 | 管理端口（Actuator） | 数据库 |
|---|---|---|---|
| order-service | 8091 | 8191 | `shop_order` |
| inventory-service | 8092 | 8192 | `shop_inventory` |
| payment-service | 8093 | 8193 | `shop_payment` |
| MySQL | 3306 | — | — |

Actuator 放在单独的管理端口，并只绑定 127.0.0.1。S5 的故障注入接口也在这个端口上，不会和业务接口一起暴露。

---

## 4. 交易流程

### 4.1 正常流程

```
客户端          order-service                    inventory-service      payment-service
  │ POST /orders  │                                     │                      │
  │──────────────▶│ ① 写订单 CREATED（幂等键唯一）       │                      │
  │               │ ② POST /reservations ──────────────▶│ 条件扣减 available    │
  │               │◀──────────── RESERVED ──────────────│                      │
  │               │ ③ 订单 → STOCK_RESERVED              │                      │
  │               │ ④ POST /payments ─────────────────────────────────────────▶│ PROCESSING → SUCCEEDED
  │               │◀────────────────────────── SUCCEEDED ──────────────────────│
  │               │ ⑤ 同一事务：订单 → PAID，写待办 CONFIRM │                    │
  │               │ ⑥ POST /reservations/{id}/confirm ─▶│ reserved → sold       │
  │               │ ⑦ 待办 → DONE                        │                      │
  │◀── 201 PAID ──│                                     │                      │
```

HTTP 调用都在数据库事务之外进行，调用下游时不占用数据库连接。

### 4.2 订单状态

```
CREATED ──预留成功──▶ STOCK_RESERVED ──支付成功──▶ PAID
   │                      │   └──支付结果未知──▶ PAYMENT_UNKNOWN ──对账──▶ PAID / CANCELLED   （S5）
   └──────────────────────┴──────────────────────────────────────────▶ CANCELLED（带 failure_reason）
```

每次状态变更都是条件更新 `UPDATE orders SET status = ? WHERE order_id = ? AND status = ?`，不会被并发的另一方覆盖。

### 4.3 分支处理

| 情况 | 订单结局 | 库存动作 | 下单接口响应 |
|---|---|---|---|
| 正常 | PAID | CONFIRM | 201 |
| 库存不足 | CANCELLED / `OUT_OF_STOCK` | 无（什么都没扣） | 201 |
| 支付被拒（`tok_declined`） | CANCELLED / `PAYMENT_DECLINED` | RELEASE | 201 |
| 库存服务连不上、超时或 5xx | CANCELLED / `INVENTORY_UNAVAILABLE` | RELEASE（可能已经预留，见 §7.3） | 503 |
| 支付服务连不上（请求没发出去） | CANCELLED / `PAYMENT_UNAVAILABLE` | RELEASE | 503 |
| 支付超时或 5xx（不知道扣没扣款） | PAYMENT_UNKNOWN，不取消 | 保持预留，等对账 | 202 |

业务结果（库存不足、支付被拒）返回 201，订单状态写在响应里。基础设施故障返回 5xx，这样能体现在 `http.server.requests` 的错误率上，便于监控。

---

## 5. 接口

所有 JSON 字段为 snake_case，金额单位为分。错误统一为 RFC 9457 Problem Details，带稳定的 `code` 字段。

### 5.1 order-service

**`POST /api/v1/orders`**

- 请求头 `Idempotency-Key`：必填，1–64 个字符。同一个用户用同一个键重试时，不会产生第二个订单。
- 请求体：

```json
{ "user_id": "u-1001", "items": [{ "sku": "SKU-1001", "quantity": 2 }], "payment_token": "tok_ok" }
```

- `items` 为 1–10 项，SKU 不能重复，`quantity` 为 1–100。
- `payment_token` 可省略，默认是 `tok_ok`。
- 响应体：

```json
{
  "order_id": "0199f0c2-…", "user_id": "u-1001", "status": "PAID",
  "amount_cents": 19800, "currency": "CNY",
  "items": [{ "sku": "SKU-1001", "quantity": 2, "unit_price_cents": 9900 }],
  "created_at": "2026-10-16T08:00:00.123Z", "updated_at": "2026-10-16T08:00:00.187Z"
}
```

`status` 为 CANCELLED 时另有 `failure_reason`。

| 状态码 | 含义 |
|---|---|
| 201 | 本次请求创建了订单（状态可能是 PAID 或 CANCELLED） |
| 200 | 幂等重放：同一个键、同样的请求体、订单已有结局；响应头 `Idempotent-Replayed: true` |
| 202 | 订单处于 PAYMENT_UNKNOWN，结果待对账（S5） |
| 400 | 校验失败，`code` = `VALIDATION_FAILED` |
| 409 | 同一个键的原请求仍在处理中，`code` = `ORDER_IN_PROGRESS`，带 `order_id` |
| 422 | 同一个键配了不同的请求体（`IDEMPOTENCY_KEY_REUSED`），或 SKU 不存在（`UNKNOWN_SKU`） |
| 503 | 下游不可用导致订单取消，带 `order_id` 和 `failure_reason` |

**`GET /api/v1/orders/{order_id}`**：200 或 404。

**`GET /api/v1/products`**：商品列表，包括 SKU、名称和单价。价格由订单服务的商品表决定，不信任客户端传入的价格。

### 5.2 inventory-service

| 接口 | 说明 |
|---|---|
| `GET /api/v1/stock`、`GET /api/v1/stock/{sku}` | 库存账目：`total`、`available`、`reserved`、`sold` |
| `POST /api/v1/stock/{sku}/restock` | `{"quantity": 20}`，补货，`total` 和 `available` 同时增加 |
| `POST /api/v1/reservations` | `{"order_id": "…", "items": [{"sku": "…", "quantity": 2}]}`，预留库存 |
| `GET /api/v1/reservations/{order_id}` | 查询预留 |
| `POST /api/v1/reservations/{order_id}/confirm` | 确认：预留的数量计入已售 |
| `POST /api/v1/reservations/{order_id}/release` | 释放：预留的数量退回可售 |

预留接口的响应：

| 状态码 | `code` | 含义 |
|---|---|---|
| 201 | — | 已预留，`status` = RESERVED |
| 200 | — | 同一个 `order_id` 的重放，返回当前状态 |
| 409 | `INSUFFICIENT_STOCK` | 某个 SKU 不够，带 `sku`、`requested`、`available`；整单什么都不扣 |
| 409 | `RESERVATION_RELEASED` | 这个订单已经释放过（包括“先释放、后到达”的预留请求，见 §7.3） |
| 422 | `UNKNOWN_SKU` / `RESERVATION_MISMATCH` | SKU 不存在 / 同一个 `order_id` 配了不同的商品 |

确认和释放都可以重复调用：已确认的再确认返回 200，已释放的再释放返回 200。确认一个已释放的预留，或释放一个已确认的预留，返回 409。

### 5.3 payment-service（第三方支付模拟）

| 接口 | 说明 |
|---|---|
| `POST /api/v1/payments` | `{"order_id", "user_id", "amount_cents", "currency", "payment_token"}`，扣款 |
| `GET /api/v1/payments/{order_id}` | 按订单查询支付，用于对账 |
| `POST /api/v1/payments/{order_id}/void`（S5） | 作废：若还没有支付记录，写入 VOIDED 占位，之后迟到的扣款请求会被拒绝 |

测试令牌仿照支付平台的沙箱：`tok_ok` 扣款成功，`tok_declined` 被拒（`INSUFFICIENT_FUNDS`），其他令牌被拒（`INVALID_TOKEN`）。

扣款接口的响应：201 表示新扣款（状态为 SUCCEEDED 或 DECLINED）；200 表示同一个 `order_id` 的重放；409 `PAYMENT_IN_PROGRESS` 表示原请求还在处理；422 `PAYMENT_MISMATCH` 表示同一个订单配了不同的金额或用户。

模拟的支付渠道耗时由 `shop.payment.gateway-latency` 配置，默认 20–60 ms 随机，让指标里的延迟分布更接近真实。

---

## 6. 数据模型

**shop_order**

```sql
product        (sku PK, name, price_cents, active)
orders         (order_id PK, user_id, idempotency_key, request_hash, status, failure_reason,
                amount_cents, currency, created_at, updated_at,
                UNIQUE (user_id, idempotency_key), INDEX (status, updated_at))
order_item     (order_id, sku, quantity, unit_price_cents, PK (order_id, sku))
inventory_task (order_id PK, action CONFIRM|RELEASE, status PENDING|DONE|FAILED, attempts,
                next_attempt_at, last_error, created_at, updated_at, INDEX (status, next_attempt_at))
```

**shop_inventory**

```sql
stock            (sku PK, total, available, reserved, sold, updated_at,
                  CHECK (available >= 0 AND reserved >= 0 AND sold >= 0),
                  CHECK (available + reserved + sold = total))
reservation      (order_id PK, status RESERVED|CONFIRMED|RELEASED, created_at, updated_at)
reservation_item (order_id, sku, quantity, PK (order_id, sku))
```

**shop_payment**

```sql
payment (payment_id PK, order_id UNIQUE, user_id, amount_cents, currency,
         status PROCESSING|SUCCEEDED|DECLINED|VOIDED, decline_reason, created_at, updated_at)
```

- ID 使用 UUIDv7 字符串，按时间递增，适合 InnoDB 的聚簇索引。
- 时间使用 `DATETIME(3)`，存 UTC。
- 种子数据：9 个商品。其中 `SKU-2001`（限量键帽）只有 20 件，用来制造售罄和并发抢购；其余每个 SKU 100000 件。

---

## 7. 幂等与一致性

### 7.1 客户端 → 订单服务

- `(user_id, idempotency_key)` 有唯一约束。
- 请求体规范化后计算 SHA-256（`request_hash`）。重放时如果哈希不同，返回 422，防止客户端误用同一个键提交了不同的订单。
- 两个相同请求并发到达时，第二个 INSERT 会在唯一索引上等待第一个事务提交，然后命中重复键，走重放分支：原请求还没完成返回 409，已完成返回 200 和同样的订单。

### 7.2 防超卖

- 扣减用条件更新：`UPDATE stock SET available = available - ?, reserved = reserved + ? WHERE sku = ? AND available >= ?`，影响 0 行即为库存不足。
- 多商品订单在同一个事务里扣减，任何一项不足就整单回滚。
- 按 SKU 排序加锁，避免两个订单以相反顺序锁行而死锁。
- 数据库层再加两道 CHECK 约束作为最后防线：数量不为负，`available + reserved + sold = total`。账目一旦算错，写入会直接失败。

### 7.3 订单服务 → 库存服务：空回滚与防悬挂

`order_id` 就是预留的主键，所以预留天然幂等。

难点在于预留请求超时：订单服务不知道库存到底扣没扣，于是取消订单并发出释放。

- **空回滚：** 释放时如果预留不存在，写入一条 RELEASED 的空记录（没有商品明细），返回 200。
- **防悬挂：** 如果原来的预留请求在网络里滞留，晚于释放才到达，它插入预留记录时会命中这条 RELEASED 记录，返回 409 `RESERVATION_RELEASED`，不会扣库存。

否则会留下一笔永远不会被确认、也不会被释放的预留，库存就此泄漏。

### 7.4 订单服务 → 支付服务

- `order_id` 唯一，一个订单最多一笔支付。
- 支付服务先写 PROCESSING 记录占住订单，再调用（模拟的）支付渠道，最后更新为结果。并发的重复请求看到 PROCESSING 会返回 409，不会第二次调用渠道。
- 支付超时 ≠ 支付失败。订单服务遇到超时或 5xx 时不取消订单，而是置为 PAYMENT_UNKNOWN，由对账（S5）通过 `GET /payments/{order_id}` 确定结果。如果查不到支付记录，先调用 `void` 写入作废占位，再取消订单。这与库存的防悬挂是同一个思路。

### 7.5 确认、释放的可靠执行

订单进入 PAID 或预留后被取消时，在**同一个事务**里写入一条 `inventory_task`（CONFIRM 或 RELEASE）。随后：

1. 请求线程立即调用库存服务，成功后将待办置为 DONE。
2. 调用失败时，待办保持 PENDING。S5 加入后台任务，按 `next_attempt_at` 指数退避重试，直到成功。
3. 库存服务明确拒绝（例如确认一个已释放的预留）时，待办置为 FAILED 并记 ERROR 日志。按设计这不应发生；一旦发生需要人工处理，重试也没有用。

库存的确认和释放都是幂等的，重试是安全的。这就是 outbox 模式，只是投递走 HTTP 而不是消息队列。

### 7.6 库存账目不变量

每个 SKU 始终满足 `available + reserved + sold = total`（由 CHECK 约束保证）。

所有订单都有结局之后，`reserved` 回到 0，并且 `sold` 的增量等于 PAID 订单的商品数量之和。请求生成工具在每次运行结束时核对这两点。

---

## 8. 可观测性

### 8.1 日志

- **控制台**：Spring Boot 默认格式，带 `traceId-spanId`。
- **文件**：`logs/<服务名>.log`，每行一条 ECS JSON。字段包括 `@timestamp`、`log.level`、`service.name`、`message`、trace 和 span ID，以及业务字段（`order_id`、`sku`、`status`、`failure_reason` 等）。
- 业务字段用 SLF4J 2 的 `addKeyValue` 写入，不拼接在 message 里，便于采集后直接按字段过滤。
- 订单、预留、支付的每次状态变化记一条 INFO；下游调用失败记 WARN；未预期的异常记 ERROR。

### 8.2 Trace

- `RestClient` 由 Spring Boot 自动配置的 Builder 创建，会自动带上 `traceparent` 头。所以同一笔下单在三个服务的日志里是同一个 trace ID。
- 每个响应都带 `X-Trace-Id` 头，请求生成工具会把失败请求的 trace ID 打印出来，方便到日志里查。
- 采样率为 100%（不导出，没有额外开销）。

### 8.3 指标

自动提供的指标：`http.server.requests`、`http.client.requests`（按下游区分）、HikariCP 连接池、Tomcat 线程、JVM。

业务指标：

| 指标 | 标签 | 服务 |
|---|---|---|
| `shop.orders` | `status`、`reason` | order |
| `shop.idempotent.replays` | `operation` | 三个服务 |
| `shop.inventory.operations` | `operation`（reserve/confirm/release）、`result` | inventory |
| `shop.inventory.tasks` | `action`（confirm/release）、`result`（done/retry/conflict） | order |
| `shop.payments` | `status`、`reason`（拒绝原因） | payment |
| `shop.payment.gateway`（计时器） | — | payment |

所有指标带 `application` 标签。

---

## 9. 请求生成工具

`shop/load-generator` 是一个 Java 命令行程序，Windows 上同样可用：

```bash
./gradlew :shop:load-generator:run --args="--rate 10 --duration 60s --duplicate-rate 0.1"
# 或者先安装：./gradlew :shop:load-generator:installDist，然后运行 build/install/load-generator/bin/load-generator（Windows 用 .bat）
```

- **下单：** 按设定速率下单（`--rate`），随机用户、每单 1–3 个不同的商品。被拒卡的比例由 `--decline-rate` 设定，限量商品 `SKU-2001` 的比例由 `--hot-rate` 设定。同时进行中的请求数达到 `--concurrency` 时，跳过这一拍并计数，这说明系统已经跟不上了。
- **重复请求：** 按 `--duplicate-rate` 用同一个 `Idempotency-Key` 再发一次，一半与原请求并发，一半在原请求完成后发出。两次响应必须指向同一个 `order_id`；其中一次是 409，说明另一次还在处理中。
- **收尾：** 发送结束后，在 `--settle-timeout` 内轮询仍未结束的订单。
- **核对：** 运行前后各取一次库存快照，核对 §7.6 的不变量：`sold` 的增量等于 PAID 订单的件数；`reserved` 的增量等于仍未结束的订单占用的件数。只要有不一致、重复请求拿到不同订单，或者出现意外的状态码，进程就以 1 退出。
- **输出：** 按 HTTP 状态码和订单结局计数，以及 p50/p95/p99 延迟。前 20 个失败请求连同它们的 trace ID 一起列出；`--report` 另外写一份 JSON。
- **前提：** 核对假设运行期间没有别的下单或补货；`--no-verify` 可以关闭核对。

**S4 实测**（开发服务器，2026-10-16）：三个服务的堆各 256 MB，10 单/秒，持续 30 秒，15% 重复请求，15% 含限量商品。

- 共 300 单，54 次重复请求。重复请求全部指向原订单：27 次重放，27 次处理中。
- 结局：PAID 264，OUT_OF_STOCK 28，PAYMENT_DECLINED 8。
- `SKU-2001` 恰好卖出 20 件，没有超卖。库存账目核对通过：卖出 1066 件，没有残留的预留。
- 延迟：p50 105 ms，p95 150 ms，p99 521 ms（p99 含刚启动时的 JIT 预热）。
- 同一个 trace ID 依次出现在三个服务的日志里：创建订单 → 预留库存 → 扣款 → 确认库存。
- 三个服务的业务计数对得上：预留 272 次 = PAID 264 + 被拒 8，确认 264 次，释放 8 次。

---

## 10. 故障场景（S5）

S5 在三个服务里加入统一的故障注入：

- 在管理端口上提供 `/actuator/faults`，运行时开关故障，不用重启服务。
- 规则可以按路径设置延迟（在处理**之前**或**之后**）和错误率。
- 库存服务另外支持“锁住某个 SKU 的行 N 秒”，用来制造热点行锁等待。

计划的场景如下，具体参数在 S5 确定。

| 场景 | 注入方式 | 预期行为 | 可观测信号 |
|---|---|---|---|
| 支付响应超时（已扣款） | 支付服务处理**之后**延迟，超过订单服务的读超时 | 订单 → PAYMENT_UNKNOWN → 对账 → PAID | client 超时、WARN 日志、202 增多 |
| 支付请求滞留（迟到扣款） | 支付服务处理**之前**延迟 | 对账时作废占位，迟到的扣款被拒，订单取消，库存释放 | 同上 + 作废计数 |
| 支付错误率 | 支付服务按比例返回 503 | 同支付结果未知 | 5xx、对账量 |
| 库存服务变慢或出错 | 库存服务延迟或返回 5xx | 订单取消，返回 503；释放待办自动重试 | order 503、待办积压 |
| 热点行锁 | 锁住 `SKU-2001` 的行 | 锁等待超时、慢查询 | MySQL 锁等待、库存 p99 |
| MySQL 停止 | `docker stop` | 连接被拒，三个服务的健康检查变为 DOWN | MySQL 连接错误日志（对应 Ops-Agent 已有规则） |
| 线程池耗尽 | 支付服务变慢，同时提高下单速率 | 订单服务的 Tomcat 线程占满，延迟飙升 | Tomcat 线程、p99 |
| 进程崩溃 | 处理中途杀掉订单服务 | 重启后，后台任务把卡住的订单取消，并释放库存 | 卡单数、待办 |

每次运行场景时，请求生成工具都会把故障的注入和恢复时间、目标服务和故障类型写入 `runs/<run-id>.json`，作为 Ops-Agent 检测结果的标准答案。

---

## 11. 阶段与验收

| 阶段 | 内容 | 验收 |
|---|---|---|
| S0 | 本文档、MySQL Compose、Gradle 模块、shop-common（错误处理、UUIDv7、`X-Trace-Id`、公共默认配置） | `./gradlew build` 通过 |
| S1 | inventory-service | 预留、确认、释放及其重放；空回滚与防悬挂；并发抢购不超卖；多商品交叉下单不死锁（集成测试，真实 MySQL） |
| S2 | payment-service | 扣款、被拒、重放、参数不一致 422、并发重复只调用一次渠道 |
| S3 | order-service | 正常流程、库存不足、支付被拒、幂等重放与 422、并发重复只生成一个订单、库存服务不可用返回 503 并写入释放待办（用桩服务测试） |
| S4 | 可观测性核对与请求生成工具 | 本机端到端：三个服务的日志共享同一个 trace ID；指标可抓取；请求生成工具核对通过 |
| S5 | 故障注入与恢复机制（对账、待办重试、卡单清理）、场景脚本与标准答案记录 | 每个场景都有测试或端到端演示，故障恢复后核对仍然通过 |

---

## 12. 运行

```bash
cd deploy/shop && docker compose up -d                   # MySQL 8.4，首次启动时建库和账号
./gradlew :shop:inventory-service:bootRun
./gradlew :shop:payment-service:bootRun
./gradlew :shop:order-service:bootRun
./gradlew :shop:load-generator:run --args="--rate 5 --duration 30s"
```

Windows 上用 `gradlew.bat`，也可以直接在 IDEA 里运行三个服务的主类。
