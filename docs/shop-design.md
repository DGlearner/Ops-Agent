# 电商靶场（shop）设计

版本 v0.3 · 2026-10-16（S0–S5 已实现；R0–R5 为后续规划，见 §11–§13）

靶场是一个给 Ops-Agent 监控的最小电商系统，由订单、库存、支付模拟三个 Java 服务组成。它产生真实的日志、指标和跨服务 trace，并且可以按需注入超时和故障，让 Ops-Agent 有可以检测、可以评估的对象。

项目的主体是 Ops-Agent，靶场是被监控的业务系统。两者围绕一条可验证的闭环推进：限量商品交易 → 生成并发请求 → 观察性能和业务结果 → 注入可控故障 → Ops-Agent 检测与关联 → 根据证据优化 → 对比优化前后的结果。

---

## 1. 范围

**要做：**

- 订单、库存、支付模拟三个服务，跑通正常交易流程。
- 库存不超卖、不泄漏；重复请求不重复下单、不重复扣款、不重复扣库存。
- 结构化日志、跨服务 trace_id、Actuator/Micrometer 指标。
- 请求生成工具：持续下单、制造重复请求，结束时核对库存账目。
- 随后（S5）加入可控的超时与失败场景，并记录每次注入的起止时间，作为 Ops-Agent 评估的标准答案。
- 限量商品抢购作为一种实验模式（R1，§11）：单个 SKU、固定初始库存、每次请求买一件，用来做可重复的热点竞争实验。

**不做：** 商城前端、购物车、优惠券等营销功能、抢购活动管理后台、真实支付、用户注册登录（`user_id` 由请求直接传入）、物流、退款、异常分析（这是 Ops-Agent 的事）。

**与 Ops-Agent 的关系：** 靶场是独立系统，不依赖 Ops-Agent 的代码、数据库或 Kafka。两者只共用一个 Gradle 工程，方便在 IDEA 里一次打开。靶场的日志和指标接入 Ops-Agent 后的效果，作为两个系统的联合验收项（§12）。

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
| 指标 | Actuator + Micrometer，`/actuator/prometheus` | 现在只暴露端点。R3 起由 Prometheus 采集并计算指标规则，Alertmanager 把告警推送给 Ops-Agent（§12.3），不在 Java 里重写时序存储和规则引擎 |
| 日志 | 控制台为人读格式；文件为 JSON（ECS 格式） | 人看控制台，采集器读文件 |
| Redis | 只用于秒杀（R4，§16）：资格判断、一人一单和受理队列（Redis Stream） | 普通下单仍以 MySQL 的唯一约束和条件更新为准。R1 实测单个热点商品约 100 单/秒就到顶，而且售罄之后的请求也要排队抢热点行的锁（§11.6），这才按 §11.4 引入 Redis。库存的账本仍在 MySQL；Redis 宕机时秒杀返回 503，普通下单不受影响 |
| Kafka | **不用** | 流程是同步编排；确认、释放库存的可靠执行用订单库里的待办表加重试即可（§7.5）；没有需要异步分发的下游。秒杀的异步受理用 Redis Stream（§16.3），少一个组件；以后加入“支付异步回调”、订单事件的下游（物流、通知）时再引入 |

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

**使用边界：** 靶场没有用户认证，内部命令（预留、确认、释放、作废、补货）也不校验调用方，所以只适用于隔离的开发环境。

- 业务端口默认也只监听 127.0.0.1。需要从别的机器访问时，显式配置 `server.address`（环境变量 `SERVER_ADDRESS`，如 `0.0.0.0`），并核实实际的端口暴露范围。
- 面向不受信任的调用方之前，必须先做认证、订单归属校验、内部服务授权和补货的管理权限，并替换开发用的数据库密码。现在不做，因为这不是靶场要验证的内容。

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

按 SKU 汇总的核对发现不了相互抵消的错误，例如一单重复确认、另一单漏了确认。R1 起再逐个订单核对：

- PAID 订单的预留为 CONFIRMED，商品和数量与订单一致；
- CANCELLED 订单的预留为 RELEASED，或者不存在；
- 支付记录与订单结局一致（§10.2 的资金核对已经是逐单进行的）。


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
- **文件**：`logs/shop/<服务名>.log`，每行一条 ECS JSON。字段包括 `@timestamp`、`log.level`、`service.name`、`message`、trace 和 span ID，以及业务字段（`order_id`、`sku`、`status`、`failure_reason` 等）。
- 业务字段用 SLF4J 2 的 `addKeyValue` 写入，不拼接在 message 里，便于采集后直接按字段过滤。
- 订单、预留、支付的每次状态变化记一条 INFO；下游调用失败记 WARN；未预期的异常记 ERROR。
- 每条业务日志都带稳定的事件名 `event.action`（ECS 字段，Spring Boot 写成嵌套的 `"event":{"action":…}`）。消息正文会随细节变化，检测规则只匹配事件名和字段，不匹配正文。

| `event.action` | 服务 | 主要字段 |
|---|---|---|
| `order_created`、`order_paid`、`order_cancelled`、`order_payment_unknown`、`order_replayed` | order | `order_id`、`status`、`failure_reason` |
| `dependency_call_failed` | order | `dependency`（被调用的服务：inventory-service / payment-service，与服务目录里的名字一致）、`operation`（reserve / charge / void / confirm / release）、`failure`（NOT_SENT / TIMEOUT / ERROR）、`order_id`。秒杀调用 Redis 失败也记这个事件，`dependency` 为 `shop-redis`，`operation` 为 try_buy / read_queue / give_back 等，`failures` 为这一秒内失败的次数（每秒最多一条，§16.2） |
| `flash_sale_created`、`flash_sale_sold_out`（每个实例每场活动一条）、`flash_sale_unit_returned` | order | `flash_sale_id`、`sku`、`order_id` |
| `flash_sale_order_deferred`（WARN）、`flash_sale_order_failed`、`flash_sale_order_given_up`、`flash_sale_not_loaded`、`flash_sale_duplicate_buyer`、`flash_sale_message_invalid`、`flash_sale_consumer_failed`（ERROR） | order | `flash_sale_id`、`order_id`、`error_type`、`deliveries` |
| `payment_reconciled`、`order_abandoned` | order | `order_id`、`status` |
| `inventory_task_failed`（ERROR，需要人工处理） | order | `order_id`、`action`、`code` |
| `stock_reserved`、`reservation_rejected`、`late_reservation_rejected`、`reservation_confirmed`、`reservation_released`、`reservation_tombstone`、`reservation_replayed` | inventory | `order_id`、`code`、`sku` |
| `stock_restocked`、`stock_prepared` | inventory | `sku` |
| `payment_succeeded`、`payment_declined`、`payment_voided`、`late_charge_refused`、`charge_mismatch`、`charge_replayed`、`interrupted_charge_resolved` | payment | `order_id`、`payment_id`、`status` |
| `database_unavailable` | 三个服务 | `error_type` |
| `deadlock_retry` | 三个服务 | `operation`、`attempt` |
| `recovery_step_failed` | order、payment | `step`、`error_type` |
| `unhandled_error`（ERROR） | 三个服务 | `error` |

故障注入的日志（`shop.faults` logger）不带事件名，采集时整条丢弃（§10.1）。

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

指标标签只用有界的维度，如服务、接口模板、状态、原因。订单 ID、用户 ID、trace ID 只出现在日志里，避免指标高基数。

**积压指标**（order-service）：没有它们，就写不出“补偿不收敛”和“支付结果长时间未知”这类规则（§12.4）。每 5 s 从数据库读一次，而不是每次抓取都查；读不到数据库时报 NaN，不报过期的旧值。

| 指标 | 含义 |
|---|---|
| `shop.orders.unresolved`（`status`：created / stock_reserved / payment_unknown） | 停在这个中间状态的订单数 |
| `shop.orders.unresolved.oldest.age`（`status`），秒 | 这个状态里最早下单的那一单，距今多久 |
| `shop.inventory.tasks.pending`、`shop.inventory.tasks.failed` | 未完成的待办数、放弃重试（需要人工处理）的待办数 |
| `shop.inventory.tasks.oldest.age`，秒 | 最老的未完成待办写入至今多久 |

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
- **核对：** 运行前后各取一次库存快照，核对 §7.6 的不变量：`sold` 的增量等于 PAID 订单的件数；`reserved` 的增量等于仍未结束的订单占用的件数。再逐单查询支付记录和预留（§7.6）。只要有不一致、重复请求拿到不同订单，或者出现意外的状态码，进程就以 1 退出。
- **抢购：** `--flash-sale SKU-2001` 让每一单只买这个商品的 1 件；`--stock 100` 在开抢前通过库存服务管理端口的 `/actuator/stockprep` 把可售数量设为 100（已售的保留，账目仍平衡；有预留时拒绝，说明还有订单在途）。
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
| `duration` | 有效期，到期自动删除，默认 10 分钟，最长 1 小时 |

库存服务另有 `/actuator/rowlock`：`POST {"sku":"SKU-1001","duration":"30s"}` 持有该 SKU 库存行的行锁（像一个卡住的长事务），`DELETE` 提前释放。所有连接的锁等待超时都设为 5 s（`innodb_lock_wait_timeout`），而不是 MySQL 默认的 50 s。

**故障不会残留：**

- 每条规则到期自动删除，并记在 `shop.faults` logger 下。请求生成工具注入时，把有效期设为故障阶段的时长再加 30 s；即使它中途被杀掉，故障也会自己结束。
- 请求生成工具提前结束或按 Ctrl+C 退出时，会先清除故障。
- `mysql-down` 这类 docker 操作无法自动过期。清除失败时，控制台写明故障可能仍在生效以及失败的步骤，报告里不记录清除时间。

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

标准答案以实际执行的结果为准：注入请求失败，不能记为故障已发生；清除失败，也不能记为已恢复。R0 起只有在清除确认成功后才写 `cleared_at`，否则报告里写明残留的故障。

**实测结果**（开发服务器，2026-10-16）。每个场景 5 单/秒，基线 15 s、故障 30 s、恢复 25 s，共 350 单、约 40 次重复请求，随机种子相同。8 个场景全部通过：所有订单都有结局，重复请求全部指向原订单，库存账目和资金核对全部一致。

| 场景 | 注入 | 观察到的行为 |
|---|---|---|
| `payment-timeout` | 支付处理完后延迟 4 s 再应答（订单服务 3 s 超时） | 129 单返回 202，对账查到扣款，全部变为 PAID |
| `payment-late` | 扣款请求处理前滞留 15 s | 129 单对账时作废；随后到达的 129 笔迟到扣款全部被拒（`PAYMENT_VOIDED`）。这些订单取消、释放库存，没有扣款 |
| `payment-errors` | 30% 的扣款在处理前返回 503 | 43 单返回 202，对账作废后取消 |
| `payment-slow` | 扣款耗时 2.5 s，低于超时 | 全部成功，p95 从约 110 ms 升到 2.6 s |
| `inventory-slow` | 预留请求处理前滞留 3 s（订单服务 2 s 超时） | 150 单返回 503 并取消；释放都先于预留到达，150 个迟到的预留全部被拒（空回滚 + 防悬挂），库存没有泄漏 |
| `inventory-errors` | 预留、确认、释放 50% 返回 503 | 76 单返回 503 并取消；失败的确认和释放在故障结束后重试成功 |
| `hot-row-lock` | 锁住 `SKU-1001` 的库存行 30 s | 71 单取消，p95 4.0 s（预留超时 2 s + 释放超时 2 s）。含 SKU-1001 的 40 单中 39 单取消；**不含它的 89 单中也有 32 单被连带取消**：等锁的事务占满了库存服务的连接池（见下文） |
| `mysql-down` | 停止 MySQL 容器 30 s | 故障期间 185 次 503 `DATABASE_UNAVAILABLE`，客户端用同一个幂等键重试；MySQL 恢复后 350 单全部完成 |

**热点行锁会波及其他商品。** 含 SKU-1001 的预留在行锁上最多等 5 s，期间一直占着库存服务的一条连接；订单服务 2 s 后超时并发出释放，释放请求又要等这条预留的锁。连接需求超过连接池的 10 条时，其他商品的预留也拿不到连接，订单因此取消。R0 修复后重跑这个场景（种子不同），不含 SKU-1001 的 109 单中有 5 单被连带取消，库存服务日志里有 16 次 `Connection is not available`。连带的程度取决于这一时段的订单组合，正好落在连接池上限附近，所以两次差别很大。这是一个真实的级联故障，作为 §12.4“库存热点锁等待”场景的检测素材保留，不在靶场里规避。

第一次跑 `mysql-down` 时发现了两种被当成 500 的数据库异常：连接中途断开（`RecoverableDataAccessException`）和开启事务时拿不到连接（`CannotCreateTransactionException`）。现在都返回 503 `DATABASE_UNAVAILABLE`，重跑后三个服务都没有未处理的异常。

R0 修复后（§13）用同样的参数重跑了全部 8 个场景（种子 42），全部通过；每个场景结束后，三个服务都没有残留的故障规则。

### 10.3 没有自动化的场景

- **进程崩溃：** 在下单过程中 `kill -9` 订单服务，再重启。后台恢复会关闭中断的订单，见 §7.7；`OrderRecoveryIT` 用直接写入的中间状态订单验证了这一点。
- **线程池耗尽：** 用 `payment-slow`，同时把订单服务的 `server.tomcat.threads.max` 调小（如 20），或提高 `--rate`。开发服务器上 5 单/秒、200 个线程还到不了耗尽。

---

## 11. 限量抢购与性能实验（R1 起）

### 11.1 业务约定

- 复用 `SKU-2001` 作为限量商品。新增单商品抢购模式：每个请求只买这一个 SKU，数量为 1。现有的 `--hot-rate` 只是往普通订单里混入这个 SKU，不算抢购。多商品订单保留为回归场景。
- 固定初始库存、明确的开抢时间和有限的实验时长。由请求生成工具控制开抢，不做活动管理后台。
- 幂等键只识别同一次请求的重试，**不等于每人限购**。首期不承诺“一人一单”；以后要做，需要“活动 + 用户”的唯一约束，并用认证得到的身份代替客户端随便填的 `user_id`。R4 的秒杀（§16）做了一人一单：Redis 判断，`orders` 表上“活动 + 用户”的唯一约束兜底；身份仍然是客户端传来的 `user_id`。
- 支付继续用模拟渠道，保留成功、被拒、结果未知三种分支。
- 售罄、支付被拒是正常的业务结果；连接失败、超时、账目不一致是另外几类问题，分别计数。

### 11.2 实验数据

- 每次实验都从已知的初始状态开始。要等负载停止、所有订单和待办都有了结局，再通过专门的数据准备流程重置，不在交易进行中直接改库存。
- 基准实验期间不混入手工补货和其他请求（这也是 §9 核对的前提）。

### 11.3 三种负载分开

| 实验 | 目的 | 注意 |
|---|---|---|
| 正确性竞争 | 很小的库存、大量并发购买，检查不超卖和幂等 | 售罄后大量快速拒绝不代表处理能力高，这类实验不报吞吐 |
| 持续性能 | 库存充足（或分成多个隔离的批次），测稳定的处理能力 | 报告里写明真正走完库存和支付流程的请求比例 |
| 故障 | 基线 → 注入 → 清除 → 恢复（§10） | 记录业务影响和 Ops-Agent 的检测效果 |

### 11.4 先保证正确，再优化

抢购先用现有的数据库方案（§7.2 的条件更新）作为基线。只有实测出瓶颈之后，才按下表选择优化，而且优化后正确性不能倒退：

| 观测到的问题 | 候选优化 | 必须补充的验证 |
|---|---|---|
| 请求超过处理能力，延迟和错误持续上升 | 入口限流、限制并发、客户端退避 | 区分主动拒绝和故障；恢复后能正常受理 |
| 热点读取压到数据库 | 用 Redis 缓存商品和展示数据 | 失效、穿透和回源；缓存值不能当作库存账本 |
| 库存行竞争成为主要瓶颈 | 先缩短事务、优化 SQL 和锁顺序，再评估 Redis 预扣 | 库存以哪里为准、补偿和对账；不能只验证 Lua 扣减是原子的 |
| 同步处理扛不住突发，并且业务允许排队 | 异步受理、持久化排队、结果查询 | “受理成功”不等于“交易成功”；重复消费、积压、故障恢复、结果通知 |

业务侧的 Kafka 只在需要异步受理时引入，并且和 Ops-Agent 自己用于日志缓冲的 Kafka 分开：业务故障不能影响监控平台的链路。

### 11.5 每次实验记录什么

报告（`--report`）包括：

- 代码提交（以及是否有未提交的改动）、操作系统、CPU 核数、内存、请求生成工具是否和服务在同一台机器上；
- 负载参数和随机种子，目标速率和实际发出的速率，因同时在途请求达到上限而跳过的请求计划；
- 按状态码和结局的计数；按结局分开的延迟分位（成功、售罄、被拒、系统错误）；
- 原始请求、重复请求和重试分开计数：故障后重试成功，不能抹掉故障期间的失败和恢复耗时；
- 发送期间每秒采样一次：各服务的 CPU、数据库连接（活跃 / 等待）、请求线程、堆内存，整台机器的 CPU，请求生成工具自己的 CPU；以及这段时间 MySQL 的行锁等待次数和总时长；
- 库存账目、逐单的资金和预留核对；实验结束时未决的请求、未结束的订单、未完成和放弃的库存待办；
- 抢购时：开抢时的库存、成交单数、售罄单数、第一次答复售罄距开抢多久；故障场景的注入和清除时间。

在 CPU 配额下，JVM 自己报告的 CPU 使用率偏高（实测超过配额本身），所以判断一个服务是否受 CPU 限制，以 cgroup 的限流计数（`cpu.stat` 的 `nr_throttled`）为准。

对比优化效果时只改变目标因素，资源和负载条件保持一致，重复运行并报告波动。对外引用的吞吐、延迟、误报率等数字都必须来自保存的实验记录；有限场景下的结果不能推广成生产环境的结论。

请求生成工具和被测服务目前在同一台服务器上，报告里要注明。不要求同时运行全部服务、监控和 Agent。

### 11.6 R1 基线结果（开发服务器，2026-10-16）

**固定的实验条件。** 4 核、5.8 GB 内存的 VPS，请求生成工具和被测服务在同一台机器上。每个组件有固定的 CPU 和内存上限：在 VPS 上用 `rescap`（一个 systemd 临时作用域，同时设置 CPUQuota 和 MemoryMax），MySQL 用 Compose 的 `cpus`。

| 组件 | CPU | 内存 |
|---|---|---|
| order-service、inventory-service | 各 0.75 核 | 各 512 MB（堆 256 MB） |
| payment-service | 0.5 核 | 512 MB |
| MySQL | 1 核 | 640 MB |
| 请求生成工具 | 0.5 核 | 512 MB |

合计 3.5 核，给系统留至少 0.5 核；实测整机 CPU 峰值 3.2 核。各服务的数据库连接池为 10，Tomcat 线程上限 200。正式测量前，先用普通订单每秒 30 单预热 90 s。

不加限制时（最初两次试跑），三个服务、MySQL 和刚启动的请求生成工具一起把 4 核全部占满，测到的数字分不清是谁的瓶颈，已作废。

**正确性竞争：** 100 件库存，计划每秒 200 单、持续 10 s，同时在途上限 64，5% 重复请求、5% 被拒卡，重复 3 次。

| 次数 | 实际发出 | 成交 | 售罄 | 被拒 | 第一次售罄 | p50 / p95 | 核对 |
|---|---|---|---|---|---|---|---|
| 1 | 1155 单（112 单/s） | 100 | 1051 | 4 | 1965 ms | 458 / 1196 ms | 全部通过 |
| 2 | 1270 单（123 单/s） | 100 | 1161 | 9 | 1497 ms | 437 / 759 ms | 全部通过 |
| 3 | 1404 单（138 单/s） | 100 | 1297 | 7 | 1564 ms | 352 / 851 ms | 全部通过 |

三次都恰好卖出 100 件，没有超卖；被拒卡的订单释放了库存，又被别人买走（例如第 2 次有 9 单被拒，成交仍是 100）。每一单的支付记录和预留状态都与结局一致。售罄后的请求仍然要拿热点行的行锁才知道“不够”：每次实验有 775–873 次行锁等待，而九成以上的订单是售罄单。

**持续性能：** 库存充足（100 万件），不加重复请求和被拒卡，每档 30 s，同时在途上限 128。对照组是普通订单：在 8 个普通商品里随机买 1–3 种。

| 计划速率 | 热点商品：实际速率，p50 / p95 | 普通订单：实际速率，p50 / p95 |
|---|---|---|
| 50 单/s | 49.7 单/s，88 / 189 ms | 48.3 单/s，84 / 165 ms |
| 100 单/s | 94.1 单/s，953 / 1493 ms | 96.6 单/s，112 / 473 ms |
| 150 单/s | 99.2 单/s，1225 / 1651 ms | 120.2 单/s，999 / 1319 ms |
| 200 单/s | 98.4 单/s，1272 / 1481 ms | 116.5 单/s，1036 / 1450 ms |

在这些条件下，单个热点商品大约每秒 100 单就到顶了，普通订单大约每秒 120 单。

**瓶颈在库存的热点行，不在订单服务的 CPU：**

- 热点商品每秒 100 单以上时，库存服务的 10 条数据库连接平均有 9.4–9.7 条在用，另有 70–96 个请求在等连接。每 30 s 约 5700–6000 次行锁等待，平均每次 40–43 ms（每秒 50 单时只有 5 ms）。每一单都要更新两次热点行：预留一次，确认（预留转已售）一次。
- order-service 在 0.75 核下，整个实验过程中有 35% 的调度周期被限流，看起来也像瓶颈。对照实验：只把它放宽到 1.5 核（30 s 内只被限流 2 个周期），每秒 150 单的热点实验，实际速率仍是 91.9 单/s，库存服务依旧连接占满、平均 94 个请求排队。
- 普通订单也只有 8 个商品，每单锁 1–3 行，每秒 150 单时库存连接池同样占满，平均 83 个请求排队。

**这些结果的局限：**

- 持续性能每档只跑了一次，波动大小未知；正确性竞争重复了 3 次。
- 请求生成工具和被测服务共用一台机器。请求生成工具在 0.5 核上限内用了 0.2–0.45 核，没有成为瓶颈，但它和服务共享磁盘、内存带宽，以及 MySQL 端口转发经过的 docker-proxy。
- 只测了这一种资源配置，数字不能推广到别的机器。

**R4 的候选优化**（按 §11.4，先验证再采用，这里只是假设）：

- 售罄后不再碰热点行：比如先用不加锁的读取，或者缓存一个“已售罄”标记，直接拒绝。
- 缩短热点行的持锁时间：确认时不再更新热点行，或者把多次确认合并。
- 测量提交延迟：提交时的日志刷盘（`innodb_flush_log_at_trx_commit`）在持锁时间里的占比。
- 预期加大库存服务的连接池没有帮助，只会增加等锁的事务，但需要验证。

---

## 12. 与 Ops-Agent 的联合验收（R2 起）

### 12.1 职责

| 环节 | 负责方 |
|---|---|
| 业务日志；请求、连接池、JVM 指标 | 三个靶场服务 |
| 日志采集、解析、脱敏、规则窗口检测 | 采集器 + Ops-Agent（ingest-service、stream-processor） |
| 指标采集；阈值、比率、持续时间规则 | Prometheus |
| 接收告警（日志告警和 Alertmanager 告警）、存储、状态管理、关联 | Ops-Agent core-service |
| 整理证据、运维问答、辅助解释根因 | 以后的 Agent，只能通过 Java 提供的只读工具查询 |

关闭 Agent 后，平台仍然要能检测异常、生成和查询告警。

### 12.2 日志接入（R2 已实现）

```
logs/shop/*.log ──log-shipper──HTTP──▶ ingest-service ──▶ logs.raw ──▶ stream-processor ──▶ logs.normalized ──▶ alerts
```

- **解析：** stream-processor 的 `app_service` 解析器认出以 `{` 开头的 ECS JSON 行。事件类型取 `event.action`（§8.1）；消息取 `message`；其他字段展平成带点的名字，保留 `trace.id`、`span.id`、`order_id`、`error.type` 等，去掉进程信息和堆栈。时间用日志里的 `@timestamp`（UTC），接收时间另记在 `received_at`。
- **采集器（`tools/log-shipper`）：** 跟踪目录下的 `*.log`，只发送完整的行，每批最多 500 行。
  - 断点续传：每批被 ingest 接受后保存读到的位置；重启后从这个位置继续。
  - 重试：429、5xx 或者没有应答时重发同一批，按 Retry-After 或退避（最长 30 s）等待，这个文件暂停读取。
  - 不重复计数：每批的 Idempotency-Key 由主机、文件、文件身份、代数和起始位置算出，重试和重启都不变；ingest 据此给出同样的 `event_id`，stream-processor 只计一次（R0）。
  - 轮转：文件被换成新文件（inode 变了；Windows 上看创建时间），或者变得比已读位置还短，就从头读，代数加一，这样新内容不会被当成重复。旧文件里最后一次读取之后写入的行会丢，最多一个轮询周期（1 s）：轮转后的文件被压缩了，不读。
  - 丢弃 `shop.faults` logger 的行（§10.1）；超过 16 KB 的行截断并标记。
  - 启动时已有的文件默认从末尾开始读（`--from-start` 从头读）；运行中新出现的文件从头读。

### 12.3 指标接入

- Prometheus 抓取三个服务的 `/actuator/prometheus`。管理端口只绑定 127.0.0.1，而容器里的 Prometheus 访问不到宿主机的 localhost，到时要选定可行的抓取方式（例如 Linux 上用 host 网络，或直接在宿主机上运行），并且不能因此把故障注入接口暴露出去。
- Ops-Agent 接收 Alertmanager 的 webhook，要处理触发、重复通知和恢复三种情况。

### 12.4 第一批检测场景

| 场景 | 检测依据 | 正常对照 |
|---|---|---|
| 商品售罄 | 库存不足的业务事件或计数 | 抢购中的预期结果，只展示，不升级为系统故障 |
| 下单接口异常 | 5xx 比率、超时数、延迟 | 设最低请求量和持续时间；区分售罄、主动限流和基础设施故障 |
| 库存热点锁等待 | 锁等待、库存调用延迟、连接池压力 | 与无故障时相同的热点负载对比；只凭慢请求不能断定根因是行锁 |
| 支付响应超时 | 下游超时、PAYMENT_UNKNOWN 的数量和停留时间 | 支付被拒单独统计；对账后观察恢复和库存确认 |
| MySQL 不可用 | 连接异常、健康状态、请求错误 | 识别出三个服务的共同依赖，不重复轰炸式告警 |
| 补偿不收敛 | 待办数量、最老待办的等待时间、FAILED 待办 | 短暂重试是正常的；持续积压或需要人工处理时才升级 |

阈值用正常负载的基线和故障实验来校准。每条规则记录数据源、分组键、窗口、触发和恢复条件、抑制方式、预期结果和版本。

### 12.4.1 第一批实测（2026-10-17）

开发服务器，靶场三个服务、ingest-service、stream-processor、采集器和请求生成工具都在固定的 CPU 和内存上限下运行（§11.6）。依次运行：正常负载每秒 10 单 60 s；`mysql-down`（基线 20 s、故障 30 s、恢复 30 s，每秒 5 单）；`inventory-errors`（同样的时长）。

| 阶段 | 产生的告警 | 距注入 |
|---|---|---|
| 正常负载 | 无 | — |
| `mysql-down` | `shop-database-unavailable`（order-service） | 1.1 s |
| | `shop-recovery-failing`：order-service 的 3 个恢复步骤、payment-service 的 1 个 | 16–21 s |
| `inventory-errors` | `shop-dependency-call-failures`（order-service → inventory） | 2.3 s |

- 采集链路：采集器发送 7264 行，丢弃 2 行 `shop.faults`；`logs.normalized` 里没有一行 `shop.faults`，没有重复的 `event_id`，也没有进入 `logs.unparsed` 的靶场日志。
- `mysql-down` 时 inventory 和 payment 没有“数据库不可用”告警：订单服务自己的库先不可用，下单在写订单时就失败了，请求根本没有到达这两个服务。payment 的问题由后台恢复失败的告警反映出来。
- 一次 MySQL 停机产生了 5 条告警，来自两个服务、两条规则。它们是同一个根因，应该在关联阶段（§12.5、R3）合并成一个 Incident。
- **落库与查询（R2 后半部分，2026-10-17）：** core-service 消费 `alerts` 写入 PostgreSQL，再跑一次 `mysql-down`，同样的 5 条告警都能通过 `GET /api/v1/alerts?from=…` 查到。每条带着证据：触发它的日志消息原文、时间和 `trace.id`，可以顺着 trace 去三个服务的日志里看那次请求。

### 12.5 告警关联

先做告警的落库、查询、恢复状态和人工关联，再针对已经验证过的故障链路加自动关联，例如“库存数据库连接失败 → 库存调用失败 → 订单 5xx”。

关联的依据是环境、服务依赖、时间、异常类型和 trace。服务之间有依赖，不等于是同一次故障：同时发生的独立支付故障和独立库存故障要作为反例来测试。证据不足时保留多个 Incident，不强行合并，也不断定根因。

**实现（R3）：** core-service 的 incident 模块按 project-design §8.4 关联：每条告警指向一个主体（调用失败指向被调用方，否则是告警所在的服务），时间上接近、主体之间有依赖路径才放进同一个 Incident，只有共同依赖不算。拓扑来自 catalog 模块，local profile 下从 `catalog/topology.json` 载入靶场的四个组件。反例用新场景 `independent-faults` 产生：同时注入支付报错（30%）和库存报错（50%）。

**实测（2026-10-17）：** 先跑 `independent-faults`，4 分钟后跑 `mysql-down`。为了不用等满 15 分钟，这次把关联间隔设为 3 分钟。

| 场景 | 标准答案 | 得到的 Incident |
|---|---|---|
| `independent-faults` | 两个独立故障：payment、inventory | **2 个**：主体 inventory-service（注入后 5 s），主体 payment-service（注入后 16 s） |
| `mysql-down` | 一个故障：MySQL | **1 个**，含 5 条告警（数据库不可用 1 条，后台恢复失败 4 条），主体 order-service 和 payment-service，共同依赖提示 `shop-mysql` |

一次 MySQL 停机原来是来自两个服务、两条规则的 5 条告警，现在是一个 Incident，而两个同时发生的独立故障没有被它们共同的 MySQL 连在一起。局限见 project-design §8.4：两个相关组件在间隔内先后出现独立故障，会被放进同一个 Incident，要靠人工处理。

---

## 13. 阶段与验收

> 2026-10-17 起，路线图统一放在 project-design §14；下表保留 S0–S5 和 R0–R5 的记录，R3 之后的工作按 project-design §14.3 推进。

| 阶段 | 内容 | 验收 |
|---|---|---|
| S0 | 本文档、MySQL Compose、Gradle 模块、shop-common（错误处理、UUIDv7、`X-Trace-Id`、公共默认配置） | `./gradlew build` 通过 |
| S1 | inventory-service | 预留、确认、释放及其重放；空回滚与防悬挂；并发抢购不超卖；多商品交叉下单不死锁（集成测试，真实 MySQL） |
| S2 | payment-service | 扣款、被拒、重放、参数不一致 422、并发重复只调用一次渠道 |
| S3 | order-service | 正常流程、库存不足、支付被拒、幂等重放与 422、并发重复只生成一个订单、库存服务不可用返回 503 并写入释放待办（用桩服务测试） |
| S4 | 可观测性核对与请求生成工具 | 本机端到端：三个服务的日志共享同一个 trace ID；指标可抓取；请求生成工具核对通过 |
| S5 | 故障注入与恢复机制（对账、待办重试、卡单清理）、场景脚本与标准答案记录 | 每个场景都有测试或端到端演示，故障恢复后核对仍然通过（§10.2 实测） |
| R0 | 修正与复验，清单见下（已完成） | 每一项都有回归测试；三个服务的正常、重复、超时、补偿路径回归通过 |
| R1 | 限量抢购基线：单商品模式、逐订单核对、实验数据准备、实验记录补全（§11）（已完成，结果见 §11.6） | 并发竞争和售罄的结果可重复；订单、库存、支付逐单核对通过 |
| R2 | 第一个 Ops 闭环：靶场 JSON 日志接入；core-service 先做告警模块（落库与查询）；首个场景 `mysql-down` | 故障产生可查询的告警，带来源、时间和事件证据；重复投递不误报。**已完成**（§12.2、§12.4.1；持久层按 D1 用 MyBatis-Plus） |
| R3 | 指标与关联：Prometheus + Alertmanager 接入（积压指标已在 R1 实现，§8.3）、支付超时和库存热点锁场景、自动关联 | 正常售罄不误报；有触发和恢复记录；关联测试覆盖同时发生的独立故障。**自动关联已完成**（§12.5）；Prometheus + Alertmanager 与两个指标场景待做 |
| R4 | 按实测瓶颈选择优化（§11.4）。第一项：秒杀走 Redis（§16） | 正确性不倒退；改善由可复现的实验支撑。**秒杀已完成**（§16.6） |
| R5 | Agent 问答与受控工具排查；少量可疑行为规则 | 回答引用证据，原因不明时转人工；安全规则有正常行为作对照 |

**R0 清单：**

| 位置 | 改动 | 回归测试 | 结果 |
|---|---|---|---|
| order-service | 查询订单时先读完主记录、释放连接，再查明细，不再同时占用两条连接 | 只有一条连接时下单、重放、16 个并发查询（`OrderLookupPoolIT`） | 修复前下单返回 503，修复后通过 |
| shop-common、load-generator | 故障自动过期；提前结束或收到退出信号时清除故障；只在清除成功后写 `cleared_at`（§10.1–10.2） | 提前结束、清除失败、基线阶段结束（`ScenarioRunnerTest`）；规则到期（`ShopCommonWebTest`） | 修复前提前结束时不发清除请求；实测 SIGTERM 后故障被清除，3 s 的规则准时删除 |
| 三个服务 | 业务端口默认只监听 127.0.0.1（§3） | 从本机非回环地址连接被拒 | 实测 6 个端口都只监听 127.0.0.1 |
| stream-processor | 按 `event_id` 去重后再计数 | 同一事件投递 3 次只计 1 次，两种检测时间模式 | 修复前误报，修复后通过 |
| stream-processor | 窗口、抑制和过期清理使用同一个检测时间，日志自身的时间只作证据 | ingest 模式下加速回放一个月前的日志 | 修复前抑制期内再次告警，修复后通过 |
| stream-processor | JSON 日志按字段脱敏，文本日志保留正则兜底；脱敏后的 JSON 仍然合法 | 带引号的 `Authorization`、嵌套对象、数组、转义引号、截断的 JSON、未解析出口（均用虚构凭据） | 通过 |
| ingest-service | 开发用的日志来源只在 local profile 中启用；没有来源或在其他环境发现开发密钥时拒绝启动 | 三种配置的启动检查；实际启动 jar | `shared` 下拒绝启动，默认 `local` 下开发密钥可用 |

验收时完整构建通过（159 个测试），靶场正常下单 300 单核对通过，8 个故障场景重跑全部通过（§10.2）。

R2 不必等 R4 完成；R4 的优化项按瓶颈选择，不要求全做。Agent 框架的选择不阻塞 R0–R3。R2 起 core-service 先做告警模块，与 `project-design.md` 中 J3/J4 的划分不同，届时同步更新那份文档；D1（持久层框架）需在 R2 之前确定。

## 14. 运行

```bash
cd deploy/shop && docker compose up -d                   # MySQL 8.4，首次启动时建库和账号
./gradlew :shop:inventory-service:bootRun
./gradlew :shop:payment-service:bootRun
./gradlew :shop:order-service:bootRun
./gradlew :shop:load-generator:run --args="--rate 5 --duration 30s"
```

Windows 上用 `gradlew.bat`，也可以直接在 IDEA 里运行三个服务的主类。所有端口只监听 127.0.0.1，从别的机器访问见 §3。

把靶场日志送进 Ops-Agent（§12.2），另外需要 Ops-Agent 的 Kafka 和 Redis、ingest-service、stream-processor：

```bash
cd deploy/compose && docker compose up -d kafka redis kafka-init
./gradlew :services:ingest-service:bootRun
./gradlew :services:stream-processor:bootRun
./gradlew :tools:log-shipper:run --args="--dir logs/shop"     # 读位置保存在 logs/shop/.log-shipper-state.json
```

告警在 Kafka 的 `alerts` topic 里，可以用 Kafka UI 看（`docker compose --profile tools up -d`，http://localhost:8089）。启动 core-service（需要 Compose 里的 PostgreSQL）之后，告警落库，可以查询：

```bash
./gradlew :services:core-service:bootRun
curl "localhost:8080/api/v1/alerts?service=order-service&limit=20"     # 也可以按 severity、rule_id、from、to 过滤
```

---

## 15. 演练控制台（P1，project-design §14.3）

一个本机网页，让人不写命令就能制造各种运维问题，再看 Ops-Agent 能不能发现和解释。它属于靶场这一侧：Ops-Agent 看不到它，也不知道它做了什么。

```
浏览器 ──▶ shop/lab（127.0.0.1:8090）──┬──▶ 三个服务的业务接口（流量、攻击）
                                       ├──▶ 三个服务的管理端口、docker（故障）
                                       ├──▶ runs/lab/ground-truth.jsonl（标准答案）
                                       └──▶ core-service API（只读：Ops-Agent 看到了什么、Agent 怎么分析的）
```

**功能：**

- **流量：** 后台持续下单，可以调速率、重复请求和被拒卡的比例；开一场抢购（商品、库存、速率、时长）。正常流量是基线：这期间 Ops-Agent 不该告警。
- **故障：** 一键注入、一键清除。先接入已有的 9 个场景（§10.2，含 `independent-faults`）；之后加资源类故障（CPU、内存、线程池）、进程崩溃和坏的发布。注入的故障都有有效期，忘了清除也会自己结束。
- **攻击：** 之后加入，见 project-design §14.3 P1。
- **标准答案：** 每次注入、清除、攻击都记一条演练记录（时间、目标、描述、预期），写入 `runs/lab/ground-truth.jsonl`，不进任何服务的日志。
- **评测：** 对每次演练，查 Ops-Agent（core-service）在它的窗口里的告警和 Incident：是否检出，第一条告警晚了多久，Incident 有几个、分得对不对。
  - 窗口从开始前 30 秒到结束后 5 分钟（重试和恢复期间的影响还在），但最多到下一次演练开始，窗口互不重叠，每条告警只算一次。
  - Incident 只要在窗口内有这次演练的告警加入就算，即使它是之前就开着的。
  - **顾客视角：** 控制台记下自己发出的订单里失败的那些（服务不可用、结果未知、5xx、没有应答；售罄和被拒卡不算），写入 `runs/lab/customer-impact.txt`。故障窗口之外的告警，如果前后有顾客订单失败，说明系统真的出了问题（例如抢购压过了处理能力），不算误报；没有失败才是误报。抢购也按这个判断：告警了且订单确实失败，是“过载已告警”；订单失败却没告警，是“过载漏报”。
- **Agent 的分析：** 控制台列出最近 3 小时的 Incident 和 Agent 对它的分析（结论、假设、证据条数、被拒收的证据、建议、用了几次工具）。评测给每次故障演练的归因打分：窗口内每个 Incident 取 Agent 最新完成的分析，看第一个假设的组件是不是这次演练的根因（场景的 `target`，用 Ops-Agent 服务目录里的名字，如 `shop-mysql`）。有多个根因的场景（`independent-faults`）要每个都被某个 Incident 的第一个假设命中才算正确，命中一部分算部分正确；根因只出现在后面的假设里，单独记一类。
- **第一次演练的发现（2026-10-17）：** 每秒 50 单的抢购在这套资源下让订单服务调用库存、支付出现连接超时，Ops-Agent 告警了，这是真实的过载，最初的“抢购一律不该告警”的标准答案太粗，因此有了顾客视角。同一次演练还暴露了检测层的缺陷：抢购的告警会把之后几分钟内的独立库存故障也压住，没有新告警。为此加了“安静期”（project-design §8.3）：同一类告警安静 2 分钟以上再出现，就重新告警。但那次演练里库存故障是在抢购结束 41 秒后开始的，比安静期还短，从日志看是同一个持续的问题，所以安静期也救不了。

控制台复用请求生成工具的代码（订单生成、调用客户端、故障定义），只监听 127.0.0.1。

**演练结果（2026-10-17，开发服务器，固定资源，关联间隔临时设为 3 分钟）：**

| 演练 | 内容 | 结果 |
|---|---|---|
| 1 | 正常流量；抢购 100 件、每秒 50 单；1 分钟后 `independent-faults`；4.5 分钟后 `mysql-down` | 暴露了两个问题：评分窗口相互重叠；抢购真实过载（订单服务调用库存、支付连接超时）的告警被当成误报。另外，抢购的告警把 41 秒后的库存故障也压住了 |
| 2 | 同上，评分改正之后 | 抢购：过载已告警（13 个订单失败，5.3 s 后告警）；`independent-faults`：Incident 数量不对，库存故障并入了抢购那次仍在持续的告警（间隔短于安静期）；`mysql-down`：正确，1.5 s；误报 0 |
| 3 | 抢购；2.5 分钟后 `independent-faults` | 抢购没有过载（0 个订单失败、0 条告警，正确）；`independent-faults` 正确：2 个 Incident，库存 2.6 s、支付 20 s |
| 4 | `inventory-errors` 30 s，3 分钟后再来一次 | 两次都告警（第二次在第一次的 10 分钟抑制期内，因为中间安静超过 2 分钟） |
| 5 | 加入 Agent（基线模式，不用大模型）：正常流量；`mysql-down` 30 s；5.5 分钟后 `independent-faults` 60 s；再 5.5 分钟后 `payment-errors` 60 s | 3 次都检出、Incident 都正确（首条告警平均 4.8 s），误报 0。4 个 Incident 都在最后一条告警后 63–73 s 自动派发分析（安静 60 s，加上最多 15 s 的扫描间隔），归因 3/3 正确，没有被拒收的证据 |
| 6 | Agent 用大模型（DeepSeek `deepseek-v4-pro`），最初的提示：同演练 5 的三个故障，再加 `mysql-down` 8 s | 4 次都检出、Incident 都正确；归因 3/4。错的是 `independent-faults` 的库存那个：告警只说明 inventory 返回 503，模型因为“inventory 唯一的依赖是 MySQL”归到了 shop-mysql。同一批 Incident 基线 4/4 |
| 7 | 修正后的 Agent，全新的 Incident：同演练 6，再加调试时没出现过的 `inventory-slow` | 5 次都检出、Incident 都正确，误报 0；归因 6/6 个 Incident 正确，没有被拒收的证据。只有一条调用失败告警、看不到被调用方自身日志的 4 个，模型把置信度定为中并标为“不确定”。同一批 Incident 基线 6/6。每次分析 12–65 s、5–8 次工具调用 |

演练 5 里基线全对，是因为这三个场景的关联提示本身就指向根因：停库时多个服务同时报错，共同依赖就是 MySQL；调用失败的告警带着被调用方。它说明整条链路（检测、关联、派发、分析、校验、评分）是通的，还不能说明大模型的价值。要比出差别，需要关联提示会误导的场景，例如只有一个服务报数据库不可用（告警指向的是这个服务，不是数据库）、热点行锁、支付变慢导致订单服务线程堆积。

**演练 6 到 7 之间改了什么。** 先在提示里加了“归因到依赖要有指向它的证据”，在演练 5、6 的 9 个 Incident 上重新分析，反而错了 5 个，都错归到 shop-mysql。原因是演练里故障只隔约 5 分钟：模型用 `search_alerts` 在前后 30 分钟里看到另一次停库的告警，就当成“同时段”。于是改工具：查到的告警和 Incident 都标明与本 Incident 的时间关系（重叠，或之前、之后多少秒），默认窗口缩到 10 分钟，提示里只把重叠的算作同时发生。这 9 个 Incident 随后 9/9 正确，但它们是调试用的，不算成绩；演练 7 的 6 个新 Incident 才是验证。

**目前的结论。** 在这几种常见故障上，大模型和基线的归因一样准，都对了；大模型多出来的是可核对的解释（引用具体的告警、样本日志，例如区分 503 和读超时、指出 recovery 失败是后果）、对证据不足的如实标注和排查建议。工具给的信息不清楚时，大模型会比基线更差（演练 6），所以工具的设计和反例演练比提示更重要。要比出归因上的差别，还需要关联提示会误导的场景（上文）和更多数据（日志检索、指标）。

同样的抢购，演练 2 过载、演练 3 没有：这套资源下每秒 50 单接近处理能力的边缘，服务是否已经预热就能决定结果。


---

## 16. 秒杀（R4，2026-10-18）

R1 的抢购走普通下单接口：每个请求都在库存服务里抢同一行的行锁，售罄之后的请求也要拿到锁才知道“不够”。固定资源下单个热点商品约 100 单/秒就到顶，p95 约 1.5 s（§11.6）。秒杀把“谁能买到”的判断搬到 Redis，数据库只处理真正成交的订单。做法参考常见的 Redis + Lua + 消息队列方案，并补上它通常没有的几件事：被取消订单的名额归还、消费者崩溃后的接管、Redis 与 MySQL 的对账，以及与 R1 在同一资源下的对比实验。

```
buyer ──POST /api/v1/flash-sales/{id}/orders──▶ order-service ──Lua──▶ Redis: units left, buyers, queue (stream)
   ◀── 202 QUEUED / 200 same order / 409 SOLD_OUT                         │
                                                                          ▼ consumer group, 4 threads
                                       order-service: create order ──▶ inventory (reserve) ──▶ payment ──▶ confirm
                                                     (Saga of §4, unchanged)
                                       cancelled ──outbox──▶ unit back to Redis (Lua)
```

### 16.1 活动

- `flash_sale` 表（订单库）：活动 id、SKU、名额、开始和结束时间。管理端口 `POST /actuator/flashsales {"sku","stock","seconds"}` 立即开一场活动，写入 MySQL 后把名额载入 Redis。库存服务里的可售数量要另外准备好（`/actuator/stockprep`）：Redis 决定谁能买，库存账本仍在库存服务。
- 请求路径上的活动信息来自本地缓存（5 s）。同一个活动同时未命中只查一次数据库（`ConcurrentHashMap.compute`，防击穿）；不存在的活动 id 也缓存 1 s，并先校验 id 格式（防穿透）；缓存条目有上限。

### 16.2 受理：一次 Lua 调用

`try-buy.lua` 原子地完成：这个买家已经有订单，返回那个订单；名额用完，返回售罄；否则名额减一、记下买家和订单 id、把订单写入队列（`XADD`）。

| Redis 键 | 内容 |
|---|---|
| `shop:flash-sale:{id}:left` | 还没给出去的名额 |
| `shop:flash-sale:{id}:buyers` | 买家 → 订单 id，一人一单 |
| `shop:flash-sale:{id}:orders` | 订单 id → 买家，订单还没建好时也能查到“排队中” |
| `shop:flash-sale:queue` | 已受理订单的 Stream，所有活动共用 |

- 脚本保证任何时刻 `left + buyers = 名额`。
- **应答：** 受理返回 202 和 `QUEUED` 的订单 id（`Location` 指向查询地址）；同一个买家再来返回 200 和原订单（`Idempotent-Replayed`），不需要幂等键；售罄返回 409 `SOLD_OUT`；活动不存在 404，未开始或已结束 409。
- **Redis 不可用时返回 503，不退回数据库路径。** 秒杀就是为了挡住数据库前面的洪峰，Redis 挂了再把流量放给数据库，等于把 R1 的问题原样请回来。普通下单不依赖 Redis，照常工作；order-service 的健康检查也不计入 Redis。
- **超时不等于失败。** 命令在客户端 500 ms 超时，但可能已经写进连接，Redis 恢复后照样执行（集成测试里暂停 Redis 时实际发生了）。买家拿到 503 后重试，按一人一单拿到那次其实成功的订单，所以重试是安全的。
- 售罄的请求只读两个键，不碰数据库、不加锁。是否再加“本地售罄标记”（JVM 里记一个标志，连 Redis 都不访问）：没加，因为实测瓶颈不在 Redis（§16.6），而且它会让“已经买到的人重试”也收到售罄，破坏上面的幂等应答。
- 一个活动的键用同一个 hash tag，但队列不是，所以要求单实例 Redis；集群时每个活动用自己的队列，放在同一个 tag 下。

### 16.3 下单：消费者组

- order-service 里固定 4 个消费者线程（`shop.flash-sale.consumers`），用消费者组读队列，逐条调用原有的下单流程（§4：建单、预留、扣款、确认）。同时下单的秒杀订单最多 4 个，不论门口来了多少人：**队列削峰**，库存的热点行只承受成交的订单。
- **先处理、后确认（XACK）**。消息带着订单 id，建单是幂等的（主键冲突说明已经建过，原样返回），所以同一条消息投递两次只有一个订单。建单失败（例如 MySQL 停了）不确认，消息留在待处理列表里。
- **接管：** 待处理超过 30 s（`claim-after`）的消息，由任意实例的定时任务用 XPENDING + XCLAIM 接管；XCLAIM 带同样的最小空闲时间，只有一个实例能拿到。投递 20 次仍失败的放弃：名额还给活动，记 ERROR。
- **自己名下的待处理消息：** 读队列的命令在客户端超时、但 Redis 恢复后照样执行时，消息已经投递给这个消费者，回复却丢了。所以读失败之后，下一轮先从 ID 0 读一遍自己名下没确认的消息，不必等 30 s 的接管。集成测试里这把 Redis 恢复后的订单延迟从 30 s 降到 1 s 左右。
- 空队列时每 200 ms 读一次，不用阻塞读：阻塞读需要单独的连接，而且等待时间必须小于命令超时。
- **数据库兜底：** `orders` 表加了 `flash_sale_id` 和唯一约束 `(flash_sale_id, user_id)`。Redis 丢了买家记录（例如 AOF 来不及落盘就崩溃）时，同一个买家的第二个订单在这里失败，名额还回去，记 `flash_sale_duplicate_buyer`。

### 16.4 名额归还

订单被取消（支付被拒、库存服务不可用、对账后作废、卡单清理），无论在哪条路径上，都在取消的同一个事务里发布 `OrderCancelled` 事件，秒杀模块在同一事务里写入 `flash_sale_return`（outbox，普通订单什么也不写），提交后立即调用 `give-back.lua`，成功后删除这一行；Redis 不可用时按 2、4、8……最长 30 s 退避重试。脚本只在“买家仍然持有这个订单”时归还，所以重复执行、或者买家归还后又买了一单，都不会多加名额。订单模块不依赖秒杀模块，只发事件。

### 16.5 对账

管理端口 `GET /actuator/flashsales/{id}` 对比 Redis 和 MySQL：

- Redis 内部：`left + 持有名额的买家 = 名额`；
- 跨两边：持有名额的买家 = 未取消的订单 + 待归还的名额 + 队列里还没建单的订单，由此算出“排队中”的数量，不能为负；
- 成交的订单不超过名额。

请求生成工具（`--seckill SKU --stock N`）在每次运行结束、所有订单都有结局之后调用它，要求一致、排队为 0、待归还为 0；再加上原有的库存账目和逐单资金、预留核对（§7.6）。

**已知局限：** Redis 用 AOF、每秒刷盘，崩溃最多丢最后一秒：这一秒内被受理的买家拿到了订单 id，但订单不会被建出来，查询返回 404，名额也随之丢失（Redis 自身仍然自洽）。要零丢失需要每次写都刷盘（`appendfsync always`），吞吐会下降。活动结束后 Redis 键的清理没做。`user_id` 由客户端传入，一人一单要真正可靠，需要认证得到的身份。

### 16.6 实验结果（开发服务器，2026-10-18）

条件与 R1 相同（§11.6）：order-service、inventory-service 各 0.75 核，payment-service 0.5 核，MySQL 1 核，每个服务 512 MB（堆 256 MB）；新增的 Redis 0.5 核。先用普通订单每秒 30 单预热 90 s，再用秒杀每秒 200 单预热 20 s。与 R1 对照的三次请求生成工具仍是 0.5 核；它在每秒约 190 个请求时就用满了 0.5 核，所以计划每秒 1000 单的实验给它 1.5 核（整机 CPU 峰值 3.2 核）。原始报告在 `runs/r4-*.json`。

**正确性竞争：** 100 件库存，持续 10 s，5% 重复请求、5% 被拒卡，各 3 次。

| | R1 普通下单（计划 200 单/s） | R4 秒杀，同样参数 | R4 秒杀，计划 1000 单/s，16 个消费者 |
|---|---|---|---|
| 实际发出 | 112–138 单/s（同时在途 64 个） | 187–188 单/s（请求生成工具的 CPU 用满） | 764–944 单/s |
| 成交 / 少卖 | 100 / 0 | 100 / 0 | 100 / 0 |
| 售罄应答 p50 / p95 | 全部请求 352–458 / 759–1196 ms | 4–6 / 217–279 ms | 4–260 / 279–509 ms |
| 受理（排队）应答 p50 / p95 | — | 217–393 / 511–690 ms | 285–318 / 418–494 ms |
| 第一次售罄 | 1.5–2.0 s | 0.9–1.1 s | 0.3–0.5 s |
| 发送结束后所有订单都有结局 | 同步，应答时已有结局 | 1.2–1.4 s | 1.2–1.3 s |
| MySQL 行锁等待 | 775–873 次 | 57–75 次 | 69–122 次 |
| 核对 | 全部通过 | 全部通过；Redis 与 MySQL 对账一致 | 同左 |

被拒卡的订单取消后，名额都回到 Redis，又被后来的买家买走，所以每次都恰好卖出 100 件。受理的请求比售罄的慢，是因为它们都挤在开抢后的第一秒：消费者同时开始建单，和门口争订单服务的 0.75 核。

**持续性能：** 库存充足（100 万件），不加重复请求和被拒卡，持续 20 s，请求生成工具 1.5 核。“建单速度”是全部受理的订单除以从开始到它们全都有结局的时间。

| 消费者 | 计划速率 | 实际受理 | 受理应答 p50 / p95 | 建单速度 | 发送结束后清空队列 | 行锁平均等待 |
|---|---|---|---|---|---|---|
| 4 | 200 单/s | 200 单/s | 3 / 50 ms | 42 单/s | 74 s | 2 ms |
| 4 | 1000 单/s | 981 单/s | 5 / 120 ms | 47 单/s | 400 s | 2 ms |
| 16 | 200 单/s | 190 单/s | 18 / 64 ms | 66 单/s | 40 s | 26 ms |
| 16 | 1000 单/s | 973 单/s | 55 / 263 ms | 84 单/s | 211 s | 34 ms |

对照：R1 用普通下单接口持续抢一个热点商品，实际约 94–99 单/s，p95 约 1.5 s，行锁平均等待 40–43 ms。

**结论：**

- **门口：** 同样 0.75 核，受理能力从 R1 的约 120 单/s 提高到约 950 单/s，受理应答 p95 120 ms（4 个消费者）。售罄的请求不碰数据库，抢购时的行锁等待从约 800 次降到约 70 次。这一档的上限是订单服务的 CPU：接近每秒 1000 个请求时 0.75 核用满，Tomcat 线程一度全忙。
- **正确性没有倒退：** 所有运行零超卖、零少卖，一人一单；库存账目、逐单资金和预留、Redis 与 MySQL 对账全部一致。
- **建单：** 异步受理之后，真正写库的速度由消费者数量和热点行决定。4 个消费者每秒约 45 单：每单依次预留、扣款、确认，约 8 次事务提交，每个消费者每单约 90 ms。16 个消费者到 66–84 单/s，行锁等待随之升到 26–34 ms，接近 R1 测出的热点行上限。消费者越多，越接近同步下单时的锁竞争。默认 4 个，偏向保护数据库：限量秒杀（几百件）排队一两秒就清空，库存充足的长时间高峰才需要调大。
- **下一步（未做）：** 受理和消费分开部署、各自扩容；热点行合并扣减（一次更新预留多单）或合并确认，提高建单的上限。

**测量上的一次修正：** 请求生成工具最初在收尾时每秒把所有排队中的订单逐个查一遍。几千单排队时，这就是每秒几千个请求，打在正在建单的订单服务上，16 个消费者的建单速度因此被低估为 62 单/s。现在改为每秒查一次对账接口，等队列清空、订单都有结局，再逐单核对。

**局限：** 持续性能每档只跑了一次；请求生成工具和服务在同一台机器上，高速率实验里它用了 1–1.5 核；只测了这一种资源配置。

---

## 17. 商品缓存与一致性（R4，2026-10-19）

商品详情页（`GET /api/v1/products/{sku}`）经过两级缓存：每个实例里的本地缓存（Caffeine，5 s），再到 Redis，最后才是 MySQL。商品可以改价（`PATCH /api/v1/products/{sku}`，靶场没有认证，端口只对本机开放）。MySQL 是唯一的事实来源：**下单永远按 MySQL 里的价格计价，不读缓存**，缓存只服务展示。

```
GET  ──▶ local (5 s, per instance) ──▶ Redis entry ──▶ Bloom filter ──▶ rebuild under a lock ──▶ MySQL
PATCH ──▶ MySQL: update + version + 1, outbox row (one transaction) ──▶ invalidate: local, Redis, channel
```

### 17.1 缓存的内容

| Redis 键 | 内容 |
|---|---|
| `shop:product:{sku}` | `版本|P|商品 JSON`（商品）、`0|M`（没有这个商品，1 分钟）或 `版本|S`（已失效的标记，1 分钟） |
| `shop:product:{sku}:lock` | 重建锁 |
| `shop:product:bloom` | 所有 SKU 的布隆过滤器（Redis 8 自带），外加一个“已装满”的哨兵成员 |
| `shop:product:invalidated`（频道） | `sku|版本`：某个商品改到了这个版本，各实例丢掉本地副本 |

商品表加了 `version`，每次修改加一。版本号贯穿整套一致性设计：Redis 里的条目、失效标记、本地缓存都带着它。

### 17.2 穿透、击穿、雪崩

- **穿透**（请求不存在的商品，每次都打到数据库）：Redis 没有这一项时，先问布隆过滤器，它说“一定没有”就直接 404；漏过去的（误判率 0.1%）查完数据库后缓存“没有这个商品” 1 分钟。布隆过滤器的键不存在时 `BF.EXISTS` 也返回 0，Redis 丢了数据就会把所有商品都判成不存在；所以装满后再加一个哨兵成员，查询时一起查，哨兵不在就放行并重新装载。
- **击穿**（热点条目失效的一瞬间，所有请求一起回源）：重建时先拿锁，拿到的读数据库、写缓存；拿不到的每 20 ms 看一次缓存，最多等 500 ms，等不到才自己读数据库。锁是手写的（`RedisLock`）：`SET NX PX` 一条命令同时设置过期；值是持有者自己的随机令牌，释放时用 Lua 比较令牌再删除，避免删掉别人在我租约过期后拿到的锁。
- **雪崩**（大量条目同时过期，或 Redis 整个不可用）：过期时间 30 分钟再加 0–5 分钟随机值；Redis 不可用时直接读数据库，但最多同时 16 个（信号量），其余的等 500 ms 后返回 503，本地缓存挡住重复请求。展示数据在 Redis 宕机时降级读库，而秒杀在 Redis 宕机时拒绝（§16.2）：前者的流量数据库扛得住，后者正是为了挡住数据库前面的洪峰。

### 17.3 更新与一致性

修改商品时**先改数据库，再让缓存失效**，并且是删除（或写失效标记），不是直接改缓存：两次改价写到 Redis 的顺序可能和写到 MySQL 的顺序相反，最后留下的是旧价。

- 修改和“要失效哪个缓存”的记录（`product_cache_invalidation`，每个 SKU 一行，记最新版本）在同一个事务里写入；提交后立即失效，成功后删除这一行；Redis 不可用时按 2、4、8……最长 30 s 退避重试。没有这一步，失效时恰好 Redis 不可用，旧值就会留到过期（30 分钟）。
- **先改库再删缓存仍然有一个竞争：** 一个请求缓存未命中，从 MySQL 读到旧行；这时改价提交并删掉缓存；那个请求再把旧行写进缓存，旧值就一直留到过期。三种处理方式（`shop.product-cache.strategy`）：

| 策略 | 做法 | 结果 |
|---|---|---|
| DELETE | 改库后删除 | 竞争发生时旧值留到过期 |
| DOUBLE_DELETE | 再在 500 ms 后删一次（延迟双删） | 旧值在第二次删除之前写回的会被删掉；写回得更晚（重建卡住超过 500 ms）就留下来。第二次删除只在内存里排队，进程崩溃就丢了 |
| VERSIONED（默认） | 不删除，写一个带新版本号的失效标记；重建写缓存时用 Lua 比较版本，只覆盖更旧的版本 | 拿着旧行的重建无论多晚都写不进去：版本号就是 fencing token。标记 1 分钟后过期，所以重建卡住超过 1 分钟仍可能写入旧值，但最多留到它自己的过期时间 |

集成测试能稳定复现这个竞争：测试钩子让重建在读完 MySQL 后停住，测试这时改价，再放它继续。DELETE 下旧值留在缓存里，1 秒后仍然是旧的；DOUBLE_DELETE 下放行早于第二次删除时被修正，晚于第二次删除时留下；VERSIONED 下旧写入被拒绝（`shop.product.cache.writes{result=refused_older}`），下一次读拿到新价。

- **本地缓存：** 改价的实例立刻丢掉自己的副本，并在频道上广播 `sku|版本`，其他实例收到后丢掉副本。本地缓存记住每个 SKU 广播过的最新版本，从不保存更旧的条目（放进去之后再检查一次，避免和失效同时发生时漏掉）。Pub/Sub 不保证送达，断线期间的广播会丢，本地条目 5 s 过期兜底，所以跨实例的不一致最长 5 s。
- **为什么不用分布式锁保证一致性：** 重建锁只是为了减少回源，不是为了一致性。持有者如果卡住超过租约，别人也会拿到锁，两个人同时写；Redisson 的看门狗续期能减少这种情况，但排除不了（GC 停顿、网络分区）。真正保证“旧的写不进去”的是写入时检查版本号。

### 17.4 指标与日志

`shop.product.reads{served_by=local|redis|database|bloom}`、`shop.product.database.reads{reason=rebuild|lock_wait_timeout|redis_down|refused}`、`shop.product.cache.lock{result}`、`shop.product.cache.writes{result=written|refused_older}`、`shop.product.cache.invalidations{result}`。日志事件：`product_updated`、`product_cache_invalidation_failed`、`product_bloom_loaded`；Redis 调用失败记 `dependency_call_failed`（`dependency=shop-redis`，每秒最多一条）。

### 17.5 实验结果（开发服务器，2026-10-19）

只运行 order-service（0.75 核，与 R1、§16 相同）、MySQL 和 Redis；请求生成工具 1.5 核，用 `--product SKU` 模式（只读商品详情，可选同时改价、混入不存在的商品），每次 20 s，同时在途上限 256。order-service 的计数器在每次运行前后各读一次。原始报告在 `runs/r5-*.json`。

**缓存省下了什么**（读同一个商品，不改价）：

| 配置 | 500 次/s p50 / p95 | 1000 次/s p50 / p95 | 计划 2000 次/s：实际，p50 / p95 | MySQL 读取 |
|---|---|---|---|---|
| 本地 + Redis（默认） | 0 / 29 ms | 0 / 18 ms | 1935 次/s，0 / 83 ms | 0（本地命中 99.9%） |
| 只用 Redis | 1 / 53 ms | 0 / 63 ms | 1927 次/s，0 / 97 ms | 0 |
| 不用缓存 | 1 / 52 ms | 0 / 28 ms | 1864 次/s，4 / 170 ms；连接池 10 条全忙，最多 20 个在等 | 每次都读 |

按主键查一行本身很便宜，每秒 1000 次以内三者的延迟差不多，缓存的价值主要是让 MySQL 不再承受读流量：每秒 2000 次时，不走缓存的连接池已经占满。

**击穿：** 只用 Redis 一级，计划每秒 1000 次读同一个商品，同时每 100 ms 改价一次（每次改价都让这个热点条目失效）。

| 重建锁 | 实际读取 | 失效次数 | MySQL 读取 | 每次失效回源 | p95 |
|---|---|---|---|---|---|
| 有（默认） | 902 次/s | 62 | 62 | 1 次 | 414 ms |
| 无 | 839 次/s | 43 | 2277 | 平均 53 次；连接池最多 9 条在用、3 个在等 | 489 ms |

两次运行 order-service 的 CPU 都用满了，所以失效的次数也少于计划（改价请求自己也在排队）。

**穿透：** 计划每秒 500 次，其中 90% 请求随机的、不存在的商品（每个都不同，缓存“没有这个商品”帮不上忙）。

| 布隆过滤器 | 实际读取 | 不存在商品的应答 p50 / p95 | MySQL 读取 | 请求线程（平均 / 200） |
|---|---|---|---|---|
| 有（默认） | 479 次/s | 38 / 667 ms | 0 | 38 |
| 无 | 284 次/s（4205 次因在途上限跳过） | 789 / 2017 ms，11 个 503 | 5226 | 185 |

没有布隆过滤器时，每个不存在的商品都要拿锁、读一次 MySQL、写一个“没有”的条目，请求线程几乎全被占住，连正常商品的读取也被拖慢。

**一致性：** 三种失效策略（§17.3）分三种情况测。“读到旧值”指一次读取开始时，某次改价已经应答，返回的却还是更早的版本；滞后是它比那次改价晚了多久。每次运行结束、改价停下 6 s 后，再读 5 次，检查缓存最终是否与 MySQL 一致。

1. **服务已经预热、没有打满**（本地 + Redis，每秒 500 次读、每 50 ms 改价；只用 Redis，每秒 1000 次读、每 100 ms 改价）：三种策略都没有读到旧值，最后都一致。竞争窗口只有重建时那几毫秒，这种负载下碰不到。
2. **服务刚启动、CPU 被饿住**（每次运行都换一个新启动、不预热的 order-service，只用 Redis，计划每秒 1000 次读、实际 150–220 次）：Redis 命令在 500 ms 内拿不到应答，读取大多退回 MySQL（每次 1600–3800 次），改价后的失效也超时、进入重试（每次 4–10 次）。三种策略都读到过旧值：DELETE 0–1%，DOUBLE_DELETE 0–0.8%，VERSIONED 0–0.09%，最长滞后约 2.3 s，大致是重试的退避时间；最后都一致。这里的原因是**失效本身失败了**，版本号帮不上忙，旧值能留多久取决于 outbox 多快重试成功。
3. **专门制造竞争**（实验用的故障 `rebuild-delay=300ms`：重建读完 MySQL 后停 300 ms 再写，好比慢查询；服务预热好、不打满；只用 Redis，每秒 300 次读，每 200 ms 改价，等锁最多 1 s），每种策略 3 次 20 s 的运行，另外 6 次 4 s 的运行看改价停下后的结果：

| 策略 | 读到旧值 | 滞后 p50 / 最长 | 改价停下后仍是旧值 | 代价 |
|---|---|---|---|---|
| DELETE | 47–48% | 约 105 ms / 480 ms | 6 次中 2 次（要等 30 分钟过期） | — |
| DOUBLE_DELETE | 48–52% | 约 105 ms / 490 ms | 0 / 6 | — |
| VERSIONED | 0 | — | 0 / 6 | 每次重建都因版本更旧被拒绝（每次运行 61–64 次），缓存几乎填不上；等锁的请求等满 1 s 后回源 MySQL（每次约 2400 次），吞吐从每秒约 300 次降到约 120 次 |

- 改价比重建还快时，DELETE 和 DOUBLE_DELETE 都有一半的读取落后一个版本。延迟双删管不住这种情况：下一次改价比它的第二次删除来得更早。它只在改价停下之后起作用：500 ms 的延迟比 300 ms 的重建长，最后那个旧副本被它删掉了；而 DELETE 有三分之一的运行把旧值留到了过期。
- VERSIONED 一次旧值也没有读到，代价是可用性：数据变得比重建还快，缓存就一直填不上，读流量全部回到数据库（这里由信号量和等锁时间兜住，没有打垮它）。这类频繁变化的数据本来就不该靠缓存保证新鲜：要么接受短暂的旧值、用 DELETE 加很短的过期时间，要么直接读库。
- **怎么选：** 价格这类改得少、读得多、又不能长时间出错的数据，用 VERSIONED（默认）；能接受秒级旧值的展示数据，DOUBLE_DELETE 已经够用、而且实现最简单；单纯的 DELETE 最后可能永久停在旧值，不适合单独使用。无论哪种，**失效失败时都靠 outbox 重试兜底**（情况 2）。

**测量上的两次修正：** 第一次跑一致性对比时，每种策略的第一次运行都没有充分预热，结果混进了冷启动的影响，于是拆成了上面的情况 1 和情况 2。冷启动那组还暴露了一个真问题：Pub/Sub 的监听容器在启动时同步订阅，连接 Redis 超时（CPU 被饿住，500 ms 的连接超时不够）就让整个 order-service 启动失败，普通下单也跟着不可用。现在监听在后台订阅、失败每 2 s 重试，Redis 不可用时 order-service 照常启动（`RedisDownAtStartupIT`）。

**之后发现的问题（2026-10-19）：** 布隆过滤器每次装载都报 Redis 调用失败。`BF.MADD` 在 RESP3 下回复一组布尔值，Spring Data 的原始命令 `connection.execute` 按字节解析，解析失败时 SKU 其实已经加进去了，所以过滤器一直是好的，压测也没发现；但每次 order-service 启动都写一条 `dependency_call_failed`（`operation=bloom_load`），触发 Ops-Agent 的“调用 shop-redis 失败”告警。这是值班助手在一次 `mysql-down` 演练里指出来的：这条告警进了同一个 Incident，把规则归因带偏了（project-design §6.7）。现在用 Lua 脚本调用 `BF.MADD`（脚本里的布尔值是 1 和 0），每批 500 个 SKU，哨兵在最后一批之后单独加入；`ProductCacheIT` 检查装载成功。
