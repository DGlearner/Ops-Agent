# 电商靶场（shop）设计

版本 v0.2 · 2026-10-16（S0–S5 已实现）

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
   │                      │   └──支付结果未知──▶ PAYMENT_UNKNOWN ──对账──▶ PAID / CANCELLED
   └──────────────────────┴──────────────────────────────────────────▶ CANCELLED（带 failure_reason）
```

每次状态变更都是条件更新 `UPDATE orders SET status = ? WHERE order_id = ? AND status = ?`，不会被并发的另一方覆盖。

后台恢复任务（§7.7）保证每个订单最终都有结局：
- 停在 CREATED 或 STOCK_RESERVED 超过 60 s（`abandon-after`）的订单，说明处理它的请求已经中断，例如进程崩溃。
  - CREATED：取消（`ABANDONED`），并释放可能已经做出的预留。
  - STOCK_RESERVED：扣款请求可能已经发出，转入 PAYMENT_UNKNOWN 等待对账。
- PAYMENT_UNKNOWN 超过 5 s（`reconcile-after`）开始对账。

### 4.3 分支处理

| 情况 | 订单结局 | 库存动作 | 下单接口响应 |
|---|---|---|---|
| 正常 | PAID | CONFIRM | 201 |
| 库存不足 | CANCELLED / `OUT_OF_STOCK` | 无（什么都没扣） | 201 |
| 支付被拒（`tok_declined`） | CANCELLED / `PAYMENT_DECLINED` | RELEASE | 201 |
| 库存服务连不上、超时或 5xx | CANCELLED / `INVENTORY_UNAVAILABLE` | RELEASE（可能已经预留，见 §7.3） | 503 |
| 支付服务连不上（请求没发出去） | CANCELLED / `PAYMENT_UNAVAILABLE` | RELEASE | 503 |
| 支付超时或 5xx（不知道扣没扣款） | PAYMENT_UNKNOWN，不取消 | 保持预留，等对账 | 202 |
| 处理中途请求中断（进程崩溃） | 后台任务处理：CANCELLED / `ABANDONED`，或转入对账 | RELEASE，或等对账 | — |

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
| 202 | 订单处于 PAYMENT_UNKNOWN，结果待对账（§7.4）；用 `GET /api/v1/orders/{order_id}` 查询结局 |
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
| `POST /api/v1/payments/{order_id}/void` | 作废，除非已经扣款：若还没有支付记录，写入 VOIDED 占位（没有金额和用户），之后迟到的扣款请求返回 409 `PAYMENT_VOIDED`。无论哪种情况，都返回该订单当前的支付记录 |

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
- 支付超时 ≠ 支付失败。订单服务遇到超时或 5xx 时不取消订单，而是置为 PAYMENT_UNKNOWN，交给对账。
- **对账只调用一次 `void`，它同时完成两件事：** 返回支付的实际结局；如果还没扣款，保证以后也扣不了。这与库存的防悬挂是同一个思路。
  - SUCCEEDED → PAID，确认库存。
  - DECLINED → CANCELLED / `PAYMENT_DECLINED`，释放库存。
  - VOIDED（从未扣款） → CANCELLED / `PAYMENT_UNAVAILABLE`，释放库存。
  - PROCESSING 或支付服务不可达 → 稍后再对账。
- 如果先查询、再根据查询结果作废，两步之间迟到的扣款可能恰好到达。`void` 在一个请求里写入占位，消除了这个时间窗。
- **支付服务自己的恢复：** 停在 PROCESSING 超过 30 s 的扣款，说明请求在调用渠道之后、保存结果之前中断了。支付服务向（模拟的）渠道查询这笔扣款的结局并补记。

### 7.5 确认、释放的可靠执行

订单进入 PAID 或预留后被取消时，在**同一个事务**里写入一条 `inventory_task`（CONFIRM 或 RELEASE）。随后：

1. 请求线程立即调用库存服务，成功后将待办置为 DONE。
2. 调用失败时，待办保持 PENDING，由后台任务按 `next_attempt_at` 重试，直到成功。
   - 重试间隔指数退避：2 s、4 s、8 s……最长 30 s（`max-backoff`）。
   - 新写入的待办带 30 s 租约。只有请求线程没能记下结果（例如进程崩溃）时，后台任务才会接手，避免两边同时发送。
3. 库存服务明确拒绝（例如确认一个已释放的预留）时，待办置为 FAILED 并记 ERROR 日志。按设计这不应发生；一旦发生需要人工处理，重试也没有用。

库存的确认和释放都是幂等的，重试是安全的。这就是 outbox 模式，只是投递走 HTTP 而不是消息队列。

### 7.6 库存账目不变量

每个 SKU 始终满足 `available + reserved + sold = total`（由 CHECK 约束保证）。

所有订单都有结局之后，`reserved` 回到 0，并且 `sold` 的增量等于 PAID 订单的商品数量之和。请求生成工具在每次运行结束时核对这两点。


### 7.7 后台恢复

订单服务每 2 s（`shop.order.recovery.interval`）依次执行三步。每步都是条件更新，与在途请求并发安全，多实例部署也安全：

1. 关闭中断的订单（§4.2）。
2. 对账 PAYMENT_UNKNOWN 订单（§7.4）。
3. 重试到期的库存待办（§7.5）。

每个动作都计入 `shop.order.recovery{action, result}`，并记一条带 `order_id` 的日志。

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
| `shop.order.recovery` | `action`（abandoned/reconcile）、`result` | order |

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

## 10. 故障场景

### 10.1 注入接口

三个服务的管理端口（只监听 127.0.0.1）上都有 `/actuator/faults`，运行时增删规则，不用重启：

```bash
curl -X POST localhost:8193/actuator/faults -H 'Content-Type: application/json' \
  -d '{"path":"/api/v1/payments","method":"POST","phase":"after","delay":"4s"}'
curl localhost:8193/actuator/faults              # 当前规则
curl -X DELETE localhost:8193/actuator/faults    # 全部清除
```

| 字段 | 含义 |
|---|---|
| `path` | Spring 路径模式，如 `/api/v1/payments`、`/api/v1/reservations/**` |
| `method` | 可选，只匹配这个 HTTP 方法 |
| `phase` | `before`（默认）：处理之前延迟或失败，请求没有被处理。`after`：请求已完整处理，然后延迟应答，或把应答换成错误（应答丢失） |
| `delay` | 延迟，如 `500ms`、`4s` |
| `errors` | 失败比例，0–1 |
| `status` | 失败时的状态码，5xx，默认 503 |

库存服务另有 `/actuator/rowlock`：`POST {"sku":"SKU-1001","duration":"30s"}` 持有该 SKU 库存行的行锁（像一个卡住的长事务），`DELETE` 提前释放。所有连接的锁等待超时都设为 5 s（`innodb_lock_wait_timeout`），而不是 MySQL 默认的 50 s。

**不让标准答案泄漏给 Ops-Agent：**
- 注入的错误与服务真实的错误完全一样：同样的状态码、问题体和 `code`，客户端分不出来。
- 不记录任何故障指标。
- 规则的增删只记在 `shop.faults` 这个 logger 下，采集靶场日志时要丢掉这个 logger。
- 故障的时间线只出现在请求生成工具的报告里，作为评估的标准答案。

### 10.2 场景

请求生成工具用 `--scenario` 运行一个场景：先跑基线（`--baseline`，默认 20 s），再注入故障（`--fault`，默认 40 s），最后清除故障，观察恢复（`--recovery`，默认 30 s）。整个过程中持续下单。

```bash
./gradlew :shop:load-generator:run --args="--scenario payment-timeout --rate 5 --report runs/payment-timeout.json"
```

结束后最多等 90 s（`--settle-timeout`），让对账和重试完成。只有同时满足以下条件，进程才以 0 退出：

- 每个订单都有结局，没有停在中间状态。
- 重复请求都指向同一个订单。
- 库存账目核对通过（§7.6）。
- **资金核对通过：** 逐个订单查询支付记录。PAID 的订单都扣了款，CANCELLED 的订单都没有扣款。

故障期间返回 5xx 且没有订单号的请求（例如数据库停了），工具会像真实客户端一样，用同一个 `Idempotency-Key` 重试，直到拿到订单。这样即使订单在故障前一刻已经创建，也不会漏掉核对。

报告（`--report`）的 `scenario` 部分就是标准答案：场景名、目标服务、故障描述、预期行为，以及注入和清除的精确时间。

**实测结果**（开发服务器，2026-10-16）。每个场景 5 单/秒，基线 15 s、故障 30 s、恢复 25 s，共 350 单、约 40 次重复请求，随机种子相同。8 个场景全部通过：所有订单都有结局，重复请求全部指向原订单，库存账目和资金核对全部一致。

| 场景 | 注入 | 观察到的行为 |
|---|---|---|
| `payment-timeout` | 支付处理完后延迟 4 s 再应答（订单服务 3 s 超时） | 129 单返回 202，对账查到扣款，全部变为 PAID |
| `payment-late` | 扣款请求处理前滞留 15 s | 129 单对账时作废；随后到达的 129 笔迟到扣款全部被拒（`PAYMENT_VOIDED`）。这些订单取消、释放库存，没有扣款 |
| `payment-errors` | 30% 的扣款在处理前返回 503 | 43 单返回 202，对账作废后取消 |
| `payment-slow` | 扣款耗时 2.5 s，低于超时 | 全部成功，p95 从约 110 ms 升到 2.6 s |
| `inventory-slow` | 预留请求处理前滞留 3 s（订单服务 2 s 超时） | 150 单返回 503 并取消；释放都先于预留到达，150 个迟到的预留全部被拒（空回滚 + 防悬挂），库存没有泄漏 |
| `inventory-errors` | 预留、确认、释放 50% 返回 503 | 76 单返回 503 并取消；失败的确认和释放在故障结束后重试成功 |
| `hot-row-lock` | 锁住 `SKU-1001` 的库存行 30 s | 71 单（含 SKU-1001）取消，p95 4.0 s（预留超时 2 s + 释放超时 2 s）；其他 SKU 照常卖出 |
| `mysql-down` | 停止 MySQL 容器 30 s | 故障期间 185 次 503 `DATABASE_UNAVAILABLE`，客户端用同一个幂等键重试；MySQL 恢复后 350 单全部完成 |

第一次跑 `mysql-down` 时发现了两种被当成 500 的数据库异常：连接中途断开（`RecoverableDataAccessException`）和开启事务时拿不到连接（`CannotCreateTransactionException`）。现在都返回 503 `DATABASE_UNAVAILABLE`，重跑后三个服务都没有未处理的异常。

### 10.3 没有自动化的场景

- **进程崩溃：** 在下单过程中 `kill -9` 订单服务，再重启。后台恢复会关闭中断的订单，见 §7.7；`OrderRecoveryIT` 用直接写入的中间状态订单验证了这一点。
- **线程池耗尽：** 用 `payment-slow`，同时把订单服务的 `server.tomcat.threads.max` 调小（如 20），或提高 `--rate`。开发服务器上 5 单/秒、200 个线程还到不了耗尽。

## 11. 阶段与验收

| 阶段 | 内容 | 验收 |
|---|---|---|
| S0 | 本文档、MySQL Compose、Gradle 模块、shop-common（错误处理、UUIDv7、`X-Trace-Id`、公共默认配置） | `./gradlew build` 通过 |
| S1 | inventory-service | 预留、确认、释放及其重放；空回滚与防悬挂；并发抢购不超卖；多商品交叉下单不死锁（集成测试，真实 MySQL） |
| S2 | payment-service | 扣款、被拒、重放、参数不一致 422、并发重复只调用一次渠道 |
| S3 | order-service | 正常流程、库存不足、支付被拒、幂等重放与 422、并发重复只生成一个订单、库存服务不可用返回 503 并写入释放待办（用桩服务测试） |
| S4 | 可观测性核对与请求生成工具 | 本机端到端：三个服务的日志共享同一个 trace ID；指标可抓取；请求生成工具核对通过 |
| S5 | 故障注入与恢复机制（对账、待办重试、卡单清理）、场景脚本与标准答案记录 | 每个场景都有测试或端到端演示，故障恢复后核对仍然通过（§10.2 实测） |

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
