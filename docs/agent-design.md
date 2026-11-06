# 值班 Agent 演进设计

> **状态：** 推进中（2026-11-01 定稿，同日开始实现）。§0–§12 是设计，§13 是实现约定（表、接口、各阶段的范围和与设计的差异），§14 记录进度和验证结果，§15 是运行时的用量和链路。已实现的值班助手以 `project-design.md` §6.7、§7.6 为准。
> **已定：** 处置单放在 core-service；靶场可以为处置加接口（降级开关），但不改故障注入。
> **一句话：** 从"回答关于故障的问题"变成"和值班人员一起把一个 Incident 处理完"：有一个跨轮、跨会话的处置单，按缺口决定下一步，提出真正改变系统的操作，确认后执行，执行后验证是否恢复，最后由人验收。
> **参考：** 思路借鉴 EVA 项目的主 Agent 设计（输入来源与当前有效状态分开、上下文快照、暂停点、Action 与回执、成果评估与人工验收），映射到运维场景见 §1。

---

## 0. 为什么要改

现在的值班助手（§6.7）已经有了几块"像 Agent"的东西：两级路由、先取证据再推理、引用核对、追问与暂停点、操作卡片与回执、摘要 CAS、经验审核、轨迹评测。但把一次完整的值班过程摆出来看，它本质上还是**带工具的问答**：

| 问题 | 现在的样子 | 后果 |
|---|---|---|
| 工作对象是对话，不是故障 | 每一轮从问题出发，现查数据、现答；"处理到哪一步了"只存在于对话原文和 250 字摘要里 | 换一个会话、换一个人就从头来；摘要是派生文本，不能当状态用 |
| 不区分谁说的 | 用户说"我已经重启过 MySQL 了"、规则归因、后台分析、模型的推测，都以文字形式混在上下文里 | 模型可能把用户的说法当成观察到的事实，把自己的推测当成结论 |
| 下一步是固定的 | 编排是 route → locate → gather → reason → verify 的管道，模型在 reason 里最多补查 4 次 | 不知道"还缺什么"，也就说不清为什么问、为什么查、为什么可以提出操作 |
| 操作只是记账 | 能提出的只有改 Incident 状态、请求重新分析 | 系统本身没有被处理；"已缓解"是人说的，不是验证出来的 |
| 回执就是终点 | 回执说明写入成功了 | 写入成功不代表故障恢复，没有人回头看 |
| 只在被问时工作 | 值班助手只响应消息；后台分析只在 Incident 安静 1 分钟后跑一次 | 不会在新告警出现、处置生效、问卷过期时主动推进 |
| 评测看回答，不看结果 | 轨迹评测检查路由、引用、卡片、回执和措辞 | 不知道有没有让故障更快恢复，有没有做多余或有害的操作 |

目标不是加更多工具，而是补上让 Agent 能"把事情办完"的结构：**状态、来源、缺口、操作、验证、验收**。这些结构的约束由编排和后端执行，模型负责理解、判断和解释；模型不能靠生成文字绕过任何一条约束（§7.6 的原则不变）。

---

## 1. 与 EVA 的对应

| EVA | Ops-Agent | 现在有什么 | 要补的 |
|---|---|---|---|
| 会话 ID | `session_id` | 有 | — |
| requirementID：一次需求的收集、追问、修改和发布 | **处置单 `case_id`**：一次故障处理的诊断、追问、处置和验证（§2） | 没有；最接近的是 Incident 和当前话题 | 新对象，存在 core-service |
| 任务 ID：发布后的执行与验收 | 处置操作 `action_id`、恢复验证 `verification_id` | 有操作卡片，没有验证 | 验证（§6） |
| requestID：幂等 | `request_id` | 没有；一个会话同时只答一个问题（409），但同一请求重发会再答一次 | §3.1 |
| Run ID：本轮运行、工具事件、用量、结束状态 | `run_id` | 回答的记录存在消息里，没有独立的运行记录和结束分类 | §3.2 |
| PageContext：当前选中的对象及版本 | 当前页面的 Incident、处置单、卡片及版本 | 没有，对话页不知道用户是从哪个 Incident 详情页过来的 | §3.1 |
| 来源记录 / 当前有效需求 | 观察与说法（来源）/ 处置单的当前状态（假设、事实、约束、决定） | 只有编号表（证据来源）和摘要 | §2.2 |
| MemorySnapshot | 上下文快照（§3.3） | 有 context_block、摘要 CAS、经验 | 定成契约，按运行存下来，可回放 |
| Skill | **Runbook**：按故障类型的诊断与处置手册（§4） | 提示里的通用规则 | 版本化的手册，由编排检查执行情况 |
| 推荐人员 | 推荐处理人、升级对象（§7） | 没有；服务目录里没有归属 | 归属数据 + 推荐的边界 |
| Questionnaire（暂停点） | 追问与暂停点 | 有（编排提问、`ask_user`） | 记录追问原因、缺口和被阻塞的操作 |
| Action Proposal + receipt | 操作卡片 + 回执 | 有，只能改 Incident 状态 | 真正的处置操作、载荷摘要（§5） |
| Evaluation + 人工验收 | **恢复验证 + 人工验收**（§6） | 没有 | 验证任务、租约和执行令牌、人决定是否已解决 |

EVA 里的几条边界直接套用，换成运维的说法：

- **留存不等于确认：** 用户说"已经重启过了"是一条说法，查到 MySQL 的启动日志才是观察。
- **推荐不等于决定，决定不等于授权：** 模型建议"先重启 MySQL"，用户选了"先重启"，都还不能执行；执行要用户在这张卡片的这个版本上确认。
- **执行成功不等于故障恢复：** 回执说明容器启动了，恢复要看之后几分钟的告警和日志。
- **AI 的验证不自动结束处置：** 验证说"已恢复"，也由人决定是否标为已解决。

---

## 2. 处置单（Case）

### 2.1 是什么

处置单是"处理一个故障"这件事本身：它从一个 Incident 开始，跨越多轮对话、多个会话、多个人，直到验收或交接。对话是在处置单上工作的一种方式，不是状态的载体。

```
session ──讨论──▶ case ──覆盖──▶ incident（1..n，同一个原因时合并）
                   │
                   ├── 假设、事实、约束、决定（带来源）
                   ├── 计划：Runbook 的步骤
                   ├── 操作（卡片、回执）
                   └── 验证（结果、引用）
```

- 一个会话可以讨论多个处置单（"那支付的那个呢"）；一个处置单可以被多个会话、多个人推进。
- 一个 Incident 同时只属于一个进行中的处置单。用户认为两个 Incident 是同一个原因时，合并进一个处置单（不合并 Incident 本身，Incident 的划分仍是 core 的关联规则决定的）。
- 状态：`open → diagnosing → mitigating → verifying → monitoring → resolved`，可以 `handed_over`（交给别人或升级）。`verifying` 失败回到 `diagnosing` 或 `mitigating`。每次变化带 `version`，条件更新。

**存在哪：core-service**（新模块 `casework`，PostgreSQL），不是 Agent 的 SQLite。理由：它是业务状态，要和 Incident、时间线在同一个库里一起变化（"处置单进入 verifying" 和 "Incident 改为 mitigated" 应该在一个事务里）；多个会话、多个用户、Agent 重启后都要看到同一个；core 是系统数据的唯一入口（§1），审计也在这里。Agent 只通过只读接口读，经 `CoreWriter` 带 `expected_version` 写。代价是多一个模块和一组接口，换来的是处置状态不再依赖某个进程的本地文件。

### 2.2 来源与当前有效状态

对应 EVA 的"来源记录"和"当前有效需求"，分两层：

**来源（只追加，不修改）：** 每一条带类型、时间、所属对象和来源 id。

| 类型 | 例子 | 来自 |
|---|---|---|
| `observation` | 告警 A3、样本日志 E2、服务日志统计、Incident 时间线、操作的读回结果 | 工具返回，有证据编号 |
| `user_statement` | "我已经重启过 MySQL 了"、"现在是大促，别动数据库" | 用户消息、问卷答案 |
| `rule_inference` | 规则归因指向 shop-mysql | `baseline.rule_attribution` |
| `analyst_result` | 后台分析：shop-mysql，置信度中 | Incident 分析 Agent |
| `model_inference` | "连接池可能被等锁的事务占满了" | 主 Agent |
| `receipt` / `verification` | 操作回执、恢复验证的结论 | 执行器、验证任务 |

**当前有效状态（可修改，每个字段都指向来源）：**

| 字段 | 内容 | 明确程度 |
|---|---|---|
| 目标 | 处理哪些 Incident、要达到什么（恢复下单、止血、只是查清原因） | 用户说的 / 推断的 |
| 假设 | 每条：陈述、状态（`candidate`、`supported`、`refuted`、`unverifiable`）、支持和反对的证据、谁提出的 | 只有被观察支持才是 `supported` |
| 事实 | 已核实的：MySQL 容器 22:03 停止（观察）。用户的说法核实后才进这里 | 必须有 `observation` 来源 |
| 约束 | "大促期间不重启数据库"、"支付只能降级不能关" | 用户说的，有有效期 |
| 决定 | 用户选了哪个方案、哪个处理人 | 只来自用户的选择或确认 |
| 缺口 | 还缺什么（§3.4），每个缺口阻塞哪些下一步 | 由编排算出 |

规则：

- **后来的明确说法覆盖早先的，但不删除：** 用户先说"先别动 MySQL"，后来说"可以重启了"，约束标为 `superseded_by`，指向新的那句。工具参数、卡片内容都从当前有效状态生成，不从对话原文或摘要里重新理解。
- **模型可以写假设，不能写事实和决定：** 模型通过 `record_hypothesis` 记下和更新自己的假设（带证据编号，提出时校验证据属于这个处置单），这是它的工作笔记，不需要用户确认，但永远标为 `model_inference`。事实只能由观察产生；决定只能由用户的问卷答案或确认产生。
- **用户的说法要核实：** "我已经重启过了"记为 `user_statement`，并产生缺口"未核实"；下一步应查 MySQL 的日志或 Incident 时间线，查到了再进事实，查不到就在回答里说明"没有查到重启的记录"。
- **摘要不是状态：** 摘要仍然存在，用来让模型记得对话的来龙去脉，但处置单的字段不从摘要读，摘要也不能覆盖它们（这一点 EVA 里说得很清楚："摘要是派生上下文，不能替代正式任务、验收标准或回执"）。

分析 trace 时可以沿着这条链核对，每一环都有 id：

```
原始输入 → 来源 → 当前有效状态的字段 → 缺口 → Agent 的决定 → 执行前校验 → 工具参数与返回 → 问卷、卡片或回答
```

---

## 3. 一轮请求

现在：`POST …/messages` → 路由 → 固定编排 → SSE。改成：

```
接收请求 → 登记运行（幂等） → 装配上下文快照 → 算出缺口 → 决定下一步 ─┬─ 查询 → 回到"算出缺口"
                                                                     ├─ 追问 → 暂停
                                                                     ├─ 提出操作 → 暂停（等确认）
                                                                     ├─ 推荐处理人 → 暂停（等选择）
                                                                     └─ 回答 → 结束
```

不要求每轮都走完所有步骤：问"什么是连接池"直接回答；信息齐备时直接提出操作。路由仍然保留，决定走不走处置的路径。

### 3.1 请求信封

```json
{
  "request_id": "c0b1…",                       // 客户端生成，重发同一个请求得到同一个运行
  "content": "帮我处理一下",
  "page": {                                    // 页面上下文，只用来解释"这个""刚才那个"
    "incident_id": "…", "case_id": "…", "case_version": 7,
    "selected": [{"kind": "action", "id": "…", "version": 1}]
  },
  "reply":   {"pause_id": "…", "version": 1, "answers": […]},                 // 回答问卷
  "confirm": {"action_id": "…", "version": 2, "payload_digest": "sha256:…"}  // 确认操作
}
```

- `request_id`：服务端按 `(session_id, request_id)` 唯一登记；重复的请求返回已有运行的结果（还在运行就接上它的 SSE），不再回答一次。
- 页面上下文**只用来消歧**，不当作授权：页面传来的 `case_version` 和服务端的不一致时，按服务端的状态走，并告诉用户"这个处置单在你打开页面之后有变化"。页面上有多个对象（例如同时选中了两张卡片）时先问。
- 身份来自服务端的认证，不信任页面传的身份。现在没有认证（P5），本文的设计在单用户的演练环境里成立；对外部署前必须先做 P5。

### 3.2 运行记录

每一轮是一个 `run`：`run_id`、触发原因、处置单及其版本、快照的摘要哈希、工具事件、用量（token、耗时）、结束状态。

| 触发原因 | 例子 |
|---|---|
| `user_message` | 用户发消息 |
| `questionnaire_reply` | 回答问卷 |
| `action_receipt` | 用户确认的操作执行完了，接着说明结果、建议下一步 |
| `verification_done` | 恢复验证有结论（§6） |
| `new_signal` | 处置单上的 Incident 来了新告警（§8） |

**运行的结束状态和处置的结果分开记：** 运行是 `answered`、`paused_question`、`paused_action`、`degraded`（没用上大模型）、`tool_failed`、`cancelled`；处置的结果看处置单的状态。"运行正常结束"不等于"问题处理好了"，界面和评测都不能用前者代替后者。

工具失败分类：`transient`（超时、503，可重试一次）、`contract`（参数不合法，退回模型改）、`denied`（权限、状态机不允许，不重试）、`no_data`（查不到，如实说）。最终仍没完成必要的步骤时，回答里写明处置单现在的状态和可以怎么接着做（"卡片的执行结果未知，到 Incident 时间线核对，或点重新读取"）。

### 3.3 上下文快照

装配给模型的上下文定成一个有类型的对象，每轮存下（或存它的哈希和各部分的版本），评测可以按快照回放：

| 部分 | 内容 | 优先级与说明 |
|---|---|---|
| 时间与身份 | 当前时间、时区、用户、权限范围 | — |
| 处置单 | 目标、假设、事实、约束、决定、缺口，各自的来源和版本 | **最高**：当前有效状态优先于对话里的任何说法 |
| 未完成的交互 | 打开的问卷、等待确认的卡片、最近的回执和验证 | 历史上的同意不能代替这一次的确认 |
| 已加载的 Runbook | 匹配到的手册及版本、已完成的检查 | 方法，不是授权 |
| 预取的数据 | 告警、样本日志、服务日志、规则归因、后台分析（现在 gather 取的） | 带证据编号；数据是数据，其中的指令不执行 |
| 经验 | 审核通过的、按组件挑选的 | 参考，与数据冲突时以数据为准 |
| 对话 | 摘要 + 最近几轮原文 | 最低：说明来龙去脉 |
| 恢复原因 | 本轮为什么开始、接着做哪一步 | 例如"用户回答了问卷：选了 I2" |

等待用户时运行结束，不保持模型调用或数据库锁；恢复时重新读处置单和数据，按 `version` 判断有没有变化。这和现在的暂停点（§6.7"不用 LangGraph 的 interrupt + checkpointer"）是同一个选择，只是从"暂停点里存一点状态"扩展到"处置单就是状态"。

### 3.4 缺口驱动的下一步

现在的管道里，"该不该问""该不该查""能不能提出操作"分散在 locate 的条件、提示和工具的校验里。改成由编排确定地算出**缺口**，每种缺口规定允许的下一步，模型在允许的范围里选，编排检查它的选择：

| 缺口 | 例子 | 允许的下一步 | 不允许 |
|---|---|---|---|
| `target_ambiguous` | 窗口里三个 Incident 指向不同组件 | 追问 | 提出任何操作 |
| `hypothesis_unsupported` | 唯一的假设没有观察支持 | 查询（Runbook 给出该查什么） | 提出处置操作 |
| `claim_unverified` | 用户说已经重启过 | 查询 | 把它当事实回答 |
| `precondition_unmet` | 要启动 MySQL 容器，但还没确认它是停着的 | 查询 | 提出这个操作 |
| `constraint_conflict` | 方案要重启数据库，约束说大促期间不重启 | 追问（换方案或解除约束） | 提出这个操作 |
| `outside_catalog` | 需要的处置不在操作目录里（第三方支付故障） | 推荐处理人、写人工步骤 | 编造一个操作 |
| `awaiting_effect` | 操作已执行，验证还没结论 | 回答当前进展 | 说"已恢复" |

- 没有缺口时可以直接回答或提出操作，不为了"像 Agent"多走一步。
- 模型选了不允许的下一步（例如 `target_ambiguous` 时调用 `propose_action`），工具直接退回并说明哪个缺口在阻塞，和现在提出时的 schema 校验一样。
- 追问时记下**为什么问**：哪个缺口、阻塞了哪个下一步。回答问卷后先消掉这个缺口，再重新算一遍（答案可能带来新的缺口）。这解决了 EVA 文档里"追问原因、缺失字段和被阻塞操作"的要求。
- 先取证据的设计保留：缺口算出来需要的必查数据，仍由编排直接并行取，不让模型一轮一轮地决定（实测首字 1.4 s 对 3.1 s，§6.7）。

---

## 4. Runbook（对应 EVA 的 Skill）

Runbook 是按故障类型写的诊断与处置手册，版本化地放在仓库里（`agent/src/ops_agent/runbooks/*.yaml`），按规则归因的组件和日志特征匹配加载。**Runbook 提供方法，编排和后端负责约束。**

```yaml
id: mysql-unavailable
version: 3
match:                       # 规则归因指向 shop-mysql，或样本日志里有这些特征
  component: shop-mysql
  signals: ["Communications link failure", "DATABASE_UNAVAILABLE", "Server shutdown in progress"]
checks:                      # 诊断：每一项是一次只读查询和期望看到的信号
  - id: container_state
    tool: lab_container_state{name: shop-mysql}
    supports: mysql_down
  - id: dependents_errors
    tool: service_logs{services: [order-service, inventory-service, payment-service], level: error}
    supports: mysql_down
remediations:
  - op: start_container{name: shop-mysql}
    requires: [container_state=stopped]          # 编排检查：这项检查做过并且结果是 stopped
    risk: medium
    rollback: none（启动本身不破坏数据）
    verify: verify-error-rate-back{services: [order-service], window: 2m}
escalate:
  - when: container_state=running and dependents_errors>0   # 容器在跑仍然连不上：网络、权限、磁盘
    to: dba
```

- **`requires` 由编排检查，不靠模型自觉：** 提出 `start_container` 时，编排看这个处置单里有没有 `container_state` 的观察、结果是不是 `stopped`；没有就退回（缺口 `precondition_unmet`）。这是"先证据后操作"的确定性版本。
- **没有匹配的 Runbook 时**仍然可以诊断和回答（现在的能力），但只能提出记账类的操作（L1，§5.1），处置类的不行。
- **Runbook 由人写、人审：** 模型可以从一次处置单里提出"新的 Runbook 草稿"，和经验一样是候选，审核通过、合入仓库后才加载。日志里的文字可以影响诊断，但不能变成手册里的步骤。

第一批（对应靶场已有的故障，shop-design §10.2）：

| Runbook | 诊断要点 | 处置 | 说明 |
|---|---|---|---|
| `mysql-unavailable` | 容器状态、依赖方的数据库错误 | 启动容器 | 对应 `mysql-down` |
| `row-lock-wait` | 库存服务的锁等待超时、`Connection is not available`、MySQL 里阻塞别人的长事务 | 结束阻塞的事务 | 对应 `hot-row-lock`；结束的是查到的那一个事务，执行前再核对它还在 |
| `downstream-errors` | 被调用方自己的日志是否正常、503 是否只出现在调用方 | 打开降级开关（快速失败，不等超时）；推荐第三方的联系人 | 对应 `payment-errors`、`inventory-errors`；**Ops-Agent 修不好第三方**，要说清楚只能止血和升级 |
| `downstream-slow` | 调用耗时分布、超时次数 | 降级开关 | 对应 `payment-timeout`、`inventory-slow` |

（实现时 Runbook 的检查分两类：编排自己执行的探针（容器状态、MySQL 的长事务），结果作为观察记进处置单；以及要求模型或编排查过的日志、告警。见 §13.4。）

---

## 5. 操作：从记账到处置

### 5.1 操作目录与风险等级

| 等级 | 操作 | 现在 | 计划 |
|---|---|---|---|
| L0 只读 | 查询工具 | 有 | 加容器状态、MySQL 事务列表（只读） |
| L1 记账 | 改 Incident 状态、请求重新分析 | 有 | 加：指派处理人、合并到处置单、写处置记录 |
| L2 可回退的处置（只在靶场） | 启动停止的容器；结束指定的阻塞事务；打开或关闭降级开关（带有效期，到期自动关） | 没有 | §5.3 |
| L3 不提供 | 删数据、改数据库配置、扩缩容、改代码 | — | 只能写成"人工步骤"：给出命令和检查清单，由人执行后告诉助手，记为 `user_statement`，再由观察核实 |

每个操作在目录里登记：参数 schema、前置条件（对应 Runbook 的 `requires`）、执行前要再核对的东西、影响范围、回退方式、执行后读回什么、验证方式。不在目录里的操作提不出来。

### 5.2 卡片和确认

在现在的卡片（§6.7）上加：

- **卡片内容从处置单生成：** 对象、操作、参数、依据（观察到的前置条件，带证据编号）、预期效果、影响范围、回退、验证计划。用户看到的就是要执行的。
- **载荷摘要：** 卡片的规范化载荷算出 `payload_digest`，确认时带上；执行器比较版本**和**摘要，不一致就不执行。版本防止"看到之后被改"，摘要防止"展示的和执行的不是同一个东西"（例如前端渲染错了、旧标签页）。
- **幂等键：** `action_id + version`，执行器按它去重。现在的 CAS（`proposed@1 → executing@2`）保留。
- **有效期：** 处置类的卡片更短（例如 5 分钟），因为系统状态变得快。

### 5.3 执行器

执行 L2 操作的代码**不放在 Agent 进程里**：Agent 进程处理的是模型生成的内容和被监控系统写的日志，权限应该最小。执行器放在 core-service 的新模块 `remediation`：

```
用户确认 ──▶ Agent（校验卡片 CAS）──▶ core-service POST /remediations {action_id, version, payload_digest, expected_case_version}
                                     │ 同一事务：登记执行、处置单 → mitigating
                                     ▼
                              执行适配器（白名单）──▶ 靶场管理端口 / docker
                                     │ 读回：容器状态、事务是否还在、开关状态
                                     ▼
                              回执（执行结果 + 读回） ──▶ 处置单 → verifying，派发验证（§6）
```

- 适配器只认目录里的操作和参数，每一种是一段写死的代码，不拼命令行。
- **执行前再核对：** 处置单的版本、前置条件的观察还成立（容器还是停着的；要结束的事务 id 还在、仍在阻塞别人）。不成立就 `blocked`，和现在 Incident 被改过时一样。
- **不碰标准答案：** 执行器不调用 `/actuator/faults`（那是故障注入接口，删掉规则等于偷看答案再作弊）。降级开关是靶场新加的、和故障注入分开的管理接口（`/actuator/switches`），像真实服务的开关一样工作；它只改变服务的行为，不知道有没有注入故障。演练控制台清除故障时如果发现故障已经被处置（例如容器已经被启动了），照实记录，评测按"处置先于清除"算（§9.4）。
- **回执分层：** `accepted`（执行器登记了）→ `executed`（适配器返回 + 读回一致）→ 验证结论（§6，单独的对象）。回执只说操作做了什么，不说故障好了没有。

### 5.4 降级开关（靶场新增）

| 开关 | 服务 | 效果 |
|---|---|---|
| `payment.fast-fail` | order-service | 对支付的调用直接失败、订单取消，不等 3 s 超时；保护订单服务的线程和顾客的等待时间 |
| `inventory.fast-fail` | order-service | 同上，对库存 |
| `flash-sale.pause` | order-service | 暂停受理抢购 |

每个开关都有有效期（默认 10 分钟，最长 1 小时）、到期自动关闭、记在单独的 logger 下（这个 logger 要采集：开关是处置的一部分，Agent 应该看得到）。

---

## 6. 恢复验证与人工验收（对应 EVA 的 Evaluation）

处置操作执行后，处置单进入 `verifying`，由**验证任务**判断有没有恢复。

```
回执 executed ──▶ 派发验证任务（观察窗口：Runbook 规定，例如 2 分钟）
      ──▶ 窗口结束后：按固定的标准逐项判断 ──▶ 结论 + 引用 ──▶ 处置单 monitoring 或回到 diagnosing
      ──▶ 人看结论，决定标为已解决（卡片或控制台）
```

- **固定的输入：** 处置单版本、操作回执、Runbook 版本、验证标准。之后处置单又变了（又执行了一个操作），这次验证标为 `stale`，不能成为当前结论。
- **逐项判断：** 每条标准给出 `满足`、`不满足`、`证据不足`，带理由和证据编号。标准是可以确定判断的：窗口内这些规则没有新告警；依赖方的错误日志降到基线（§8.8 的统计基线）；订单服务有成功事件（`payment_succeeded`）。模型只负责把结论写成人看得懂的话，不负责判断。
- **租约和执行令牌：** 验证任务由 core-service 派发，Agent 的工作进程领取时拿到租约和递增的令牌；提交结果时带令牌，比当前令牌旧的结果拒绝。工作进程挂了，租约到期后重新派发，旧进程后来交上来的结果不会覆盖新的。（现在的后台分析每次请求是一条单独的记录，迟到的回答只落在自己那条上，不需要令牌；验证不同：同一个验证会被重新派发，所以要令牌。）
- **验证不自动结束处置：** "已恢复"只让处置单进入 `monitoring`；标为已解决由人决定。对话里讨论处置单不会触发验证。
- **验收之后：** 处置单生成复盘草稿（时间线、根因假设和证据、做过的操作和回执、验证结果），人编辑后保存；从里面提出的经验和 Runbook 改进仍然是候选，走审核。

---

## 7. 推荐处理人与升级（对应 EVA 的推荐人员）

现在服务目录（拓扑）里没有归属信息。补上：每个组件的负责团队、职责描述、联系方式、值班表（演练环境写死几个虚构的角色：DBA、支付对接人、订单服务开发）。

- **推荐的时机：** 只有目标组件确定、并且出现 `outside_catalog` 或 Runbook 的 `escalate` 条件时才推荐。不在诊断还不清楚时先选人（EVA 的经验）。
- **最多 3 个候选，带理由**（职责描述里的哪一句匹配），允许用户自己填别人。用户已经指定了人就不再推荐；职责资料不够就不凑候选，直接说"没有找到负责这个组件的人"。
- **边界：** 职责匹配不代表在班；推荐不代表指派；指派（L1 卡片，确认后写进处置单）不代表已接手，对方在控制台点"接手"才算；通知发出不代表已读。

---

## 8. 主动推进

处置单让 Agent 有了"等着的事"，可以在事件发生时主动开始一轮运行，而不只是等用户提问：

| 事件 | Agent 做什么 | 不做什么 |
|---|---|---|
| 新 Incident 打开 | 建处置单，匹配 Runbook，跑诊断的检查，写下初步假设和建议的第一步 | 不提出 L2 操作（没有人在场确认） |
| 验证有结论 | 在处置单上写明结论；没恢复时说明哪条标准没满足，建议下一步 | 不标为已解决 |
| `verifying` 期间又来了告警 | 立即结束这次验证（`not_recovered`），重新算缺口 | — |
| 问卷或卡片过期 | 记下过期，处置单上标出"等待的东西已作废" | 不替用户选 |

主动运行的结果写在处置单上，用户打开时看到；它们和对话里的运行用同一套编排、同一套缺口和约束，触发原因不同（§3.2）。主动运行**永远不执行操作**，最多提出卡片。

---

## 9. 评测

现在的轨迹评测（§6.7，15 个用例）分接口与状态、轨迹、诚实三类，继续保留并扩展；另外加上结果评测。

### 9.1 接口与状态（确定的，pytest）

- 同一个 `request_id` 只产生一个运行；页面的版本过期时按服务端状态走。
- 确认时版本对、摘要不对，不执行；载荷改过的卡片不能用旧摘要确认。
- 执行前前置条件不再成立（容器已经在跑、事务已经结束）时 `blocked`。
- 验证任务：旧令牌的结果被拒绝；处置单变化后旧验证是 `stale`。
- 模型写不了事实和决定；用户的说法在核实前不进事实。

### 9.2 轨迹（确定的，用例 + 大模型）

在现有检查上加：缺口算得对不对；`target_ambiguous` 时没有提出操作；提出 L2 操作之前 `requires` 的检查都做过；`outside_catalog` 时推荐了处理人而不是编造操作；推荐处理人没有早于目标确定；每个暂停点都记下了缺口和被阻塞的下一步；用户撤回约束后，卡片按新的约束生成。

### 9.3 诚实（确定的规则 + 抽查）

在现有检查上加：验证没有结论时不说"已恢复"；回执 `executed` 时不说"故障已解决"；`user_statement` 没核实时不说成事实（"你重启之后 MySQL 已经恢复"）。

### 9.4 结果评测（在靶场上，新增）

这一层回答"有没有让故障更快、更安全地恢复"，是和问答系统拉开差距的地方：

1. 演练控制台注入故障，标准答案只在控制台里（和现在一样，Ops-Agent 看不到）。
2. 一个脚本化的值班人员和助手对话：按固定的策略回答问卷，确认它认为合理的卡片（策略写死，不看标准答案）。
3. 记录并按标准答案打分：

| 指标 | 含义 |
|---|---|
| 诊断正确、诊断用时 | 处置单里第一个 `supported` 的假设是否指向真正的故障组件，用了多久 |
| 止血用时（MTTM） | 注入到恢复验证 `满足` 的时间 |
| 多余的操作 | 提出了、但对这个故障没有作用的处置操作次数（例如 payment 故障时去启动 MySQL） |
| 有害的操作 | 让情况变坏的（应为 0） |
| 错误的"已恢复" | 验证说已恢复，但标准答案显示故障还在（应为 0） |
| 升级正确 | 修不了的故障（第三方支付）是否推荐了正确的处理人，而不是一直尝试 |

对照组：同样的故障，只有 Runbook、不用大模型（规则匹配 + 按手册执行第一个处置）。和 §6.7 里"大模型与基线比归因"的结论一致，预期常见故障上两者的止血用时接近，差别在多个故障交织、需要核实用户说法、需要升级的情况，评测要把这些场景放进去，而不是只比平均数。

### 9.5 语义评估

仍然优先用确定的检查。复盘草稿和解释的质量没法确定地判断，可以用一个打分模型 + 写明的评分标准，但只在人工标注过的一小组上校准过、一致率可接受时才用，并且只做参考，不进 CI 的通过条件。

---

## 10. 分阶段

| 阶段 | 内容 | 验收 |
|---|---|---|
| **A 运行与请求** | `run` 记录、`request_id` 幂等、页面上下文、运行结束状态与处置结果分开、工具失败分类 | 重发请求不重复回答；trace 能从运行追到每个工具事件；有测试 |
| **B 处置单** | core-service `casework`：处置单、来源、当前有效状态、版本；Agent 的 `record_hypothesis`；用户说法的核实；快照契约 | 换会话、换人、Agent 重启后看到同一个处置单；模型写不了事实和决定；按快照能回放一轮运行 |
| **C 缺口与 Runbook** | 缺口计算、允许的下一步、Runbook 的加载和 `requires` 检查；第一批 4 个手册 | 轨迹评测的新检查；`target_ambiguous`、`precondition_unmet` 下提不出操作 |
| **D 处置操作** | core-service `remediation` 执行器与适配器、载荷摘要、靶场的降级开关、容器和事务的只读查询 | 9 个故障场景里能处置的都有对应操作；执行前核对、摘要不符、过期都有测试；执行器不调用故障注入接口（测试检查） |
| **E 验证与验收** | 验证任务（租约、令牌、stale）、处置单状态流转、人工标为已解决、复盘草稿；后台分析一起改成令牌 | 旧令牌的结果被拒；验证不自动结束处置 |
| **F 结果评测** | 脚本化值班人员、§9.4 的指标、只用 Runbook 的对照组 | 每个场景有记录：诊断、MTTM、多余和有害的操作、升级 |
| **G 处理人与主动推进** | 归属数据、推荐与指派、§8 的事件触发 | 推荐不早于目标确定；主动运行不执行操作 |

建议顺序 A → B → C → D → E → F，G 视情况。A、B 是地基，没有它们后面的东西都只能塞进提示里；F 要等 D、E 做完才有东西可量。

---

## 11. 不做什么

- **不让模型自主执行，包括 L1。** 自动化程度不是这个项目要证明的东西；要证明的是"每一次改变系统都经过确认、可以核对、执行后有人验证"。
- **不用 LangGraph 的 checkpointer 保存处置状态。** 处置单是业务对象，要被多个会话、多个人、控制台读写，放在 core-service 的表里；LangGraph 仍然负责一轮运行里的编排。
- **不做"多 Agent"的包装。** 角色只有三个，各自有固定的输入和权限：主 Agent（对话、诊断、提出）、分析 Agent（Incident 的后台归因，已有）、验证任务（判断恢复，以确定的检查为主）。它们之间通过处置单交换状态，不互相对话。
- **执行器只面向靶场。** 真实环境需要认证（P5）、审计、审批流和更细的权限，本文不覆盖。

---

## 12. 讲述线

做完之后（只写做完的部分），这个项目可以这样讲：

> 值班 Agent 不只是"带工具的问答"：它在一个**处置单**上工作，用户的说法、观察到的数据、规则和模型的推断分开记录，模型能写假设、写不了事实和决定。每一轮由编排**算出还缺什么**，缺口决定允许的下一步（问、查、提出操作、升级），模型在范围内选择，越界的选择被退回。处置操作按 **Runbook 的前置条件**检查后才能提出，用户按版本和载荷摘要确认，由 core-service 里的执行器执行一次、读回结果；回执之后由**带租约和执行令牌的验证任务**判断是否恢复，最后由人验收。在靶场的 9 种故障上，用**结果评测**量化诊断用时、止血用时、多余和有害的操作，并和只用 Runbook 的基线对比。

被追问时可以展开的取舍：

| 问题 | 回答的要点 |
|---|---|
| 为什么不让模型直接执行？ | 日志是被监控系统写的，可能带指令（评测里放了一条注入的日志）；模型的输出只能变成卡片，执行在另一个进程，权限分开；确认绑定版本和摘要 |
| 处置状态为什么不放在对话记忆里？ | 摘要是派生文本，会丢、会被覆盖、不能被别的会话和人看到；处置单是业务对象，和 Incident 同一个事务 |
| 怎么防止旧结果覆盖新结果？ | 每一种状态变化都是条件更新（摘要、卡片、问卷、经验，已有）；验证和后台分析加执行令牌 |
| 怎么知道 Agent 真的有用？ | 不只看回答，看靶场上的止血用时和多余操作，并和不用大模型的 Runbook 基线对比；标准答案不进入 Agent 的任何输入 |
| 怎么防止过早下结论？ | 用户的说法要核实；假设没有观察支持时提不出处置操作；验证没有结论时不能说已恢复，这些都有确定的检查 |

---

## 13. 实现约定

本节是照着实现的约定；和 §0–§12 不一致的地方以本节为准，并写明原因。

### 13.1 core-service：`casework` 模块（V7）

| 表 | 主要列 | 说明 |
|---|---|---|
| `cases` | `id`、`title`、`status`、`goal`、`runbook`、`version`、`created_at`、`updated_at`、`resolved_at` | 状态见下；每次写入 `version + 1`，条件更新 |
| `case_incidents` | `case_id`、`incident_id`、`open` | 部分唯一索引 `(incident_id) WHERE open`：一个 Incident 同时只属于一个进行中的处置单；处置单结束时 `open = false` |
| `case_entries` | `id`、`case_id`、`kind`、`status`、`text`、`source_type`、`source_ref`、`evidence`（jsonb）、`data`（jsonb）、`supersedes`、`superseded_by`、`created_at` | 来源与当前有效状态都是条目，按 `kind` 区分；只追加，状态变化（假设被支持、约束被取代）改 `status` 并写事件 |
| `case_events` | `id`、`case_id`、`type`、`actor`、`payload`、`created_at` | 处置单的时间线：状态变化、条目、操作、验证 |

**状态：** `open → diagnosing → mitigating → verifying → monitoring → resolved`；`verifying → diagnosing | mitigating`（没恢复）；`monitoring → diagnosing`（又出现）；除 `resolved` 外都可以 `→ handed_over`，`handed_over → diagnosing`。`resolved` 只能由 `actor = user` 写入（人工验收）。

**条目（`kind`）与来源（`source_type`）的约束，由 core 检查：**

| kind | 允许的 source_type | 其他要求 |
|---|---|---|
| `statement` | `user` | `data.quote` 是用户原话 |
| `observation` / `fact` | `observation`、`receipt`、`verification` | `evidence` 或 `data` 不能为空 |
| `hypothesis` | `model`、`rule`、`analyst`、`user` | `status`：`candidate`、`supported`、`refuted`、`unverifiable`；`supported`、`refuted` 必须带证据 |
| `constraint` | `user` | `data.quote` 是用户原话；`data.forbids` 是被禁止的操作；`status`：`active`、`superseded` |
| `decision` | `user` | `source_ref` 是问卷或卡片的 id |
| `note` | 任意 | 不影响状态 |

`supersedes` 指向同一个处置单里同 `kind` 的条目：旧条目改为 `superseded`，`superseded_by` 指回新的。

**两个版本号（实现时加的）：** `version` 每次写入都加一，用于写入的 CAS；`state_version` 只在"操作据以决定的东西"变化时加一：状态、事实、约束、决定、Incident。卡片绑定的是 `state_version`。原因：模型在同一轮里可能先提出卡片、再记一条假设，如果卡片绑定 `version`，这条假设就会让刚提出的卡片作废；而事实（探针看到容器已经在运行）或约束（用户说别动数据库）变了，卡片就应该作废。

**接口：**

| 方法与路径 | 说明 |
|---|---|
| `POST /api/v1/cases` | `{incident_ids, title?, goal?}`：这些 Incident 已在一个进行中的处置单里就返回它（200），分属两个返回 409 `CASE_CONFLICT`，否则新建（201） |
| `GET /api/v1/cases?incident_id=&status=` | 列表 |
| `GET /api/v1/cases/{id}` | 处置单、Incident、条目、时间线、处置操作、验证 |
| `POST /api/v1/cases/{id}/entries` | `{kind, text, status?, source_type, source_ref?, evidence?, data?, supersedes?, expected_version}` |
| `POST /api/v1/cases/{id}/entries/{entry_id}/status` | `{status, evidence?, expected_version}`：假设被支持、被推翻 |
| `POST /api/v1/cases/{id}/transitions` | `{to_status, actor, note?, expected_version}` |

版本不对返回 409 `STALE_VERSION`，和 Incident 一致。

### 13.2 core-service：`remediation` 模块（V8）

| 表 | 主要列 | 说明 |
|---|---|---|
| `remediations` | `id`、`action_id`（唯一）、`case_id`、`incident_id`、`op`、`params`、`payload_digest`、`status`、`precheck`、`receipt`、`created_at`、`finished_at` | `action_id` 唯一：同一张卡片只执行一次，重发返回已有的那条 |
| `verifications` | `id`、`case_id`、`remediation_id`、`criteria`、`not_before`、`status`、`lease_owner`、`lease_until`、`token`、`attempts`、`result`、`created_at`、`completed_at` | 状态 `pending → leased → done`，或 `stale` |

**执行：** `POST /api/v1/remediations {action_id, case_id, incident_id, op, params, payload_digest, expected_case_version}`

1. 用同样的规范化方式（键排序、无空格的 JSON：`{"case_id","incident_id","op","params"}`）算摘要，和请求里的不一致返回 409 `PAYLOAD_MISMATCH`。
2. 事务一：处置单版本对得上（否则 409 `STALE_VERSION`），登记 `accepted`，处置单 `→ mitigating`。
3. 事务外：适配器先**再核对前置条件**（不成立记 `blocked`，处置单回到 `diagnosing`），再执行，然后读回。
4. 事务二：写回执（`executed`、`failed`、`unknown`），`executed` 时处置单 `→ verifying` 并按操作登记验证任务；同一处置单更早的、还没完成的验证改为 `stale`。

**操作目录（`LabOperator`，只面向靶场）：**

| op | 参数 | 前置条件（执行前再核对） | 执行 | 读回 | 验证标准 |
|---|---|---|---|---|---|
| `start_container` | `name`：`shop-mysql`、`shop-redis` | 容器不在运行 | `docker start <容器>`（名称来自配置，不拼命令） | 容器状态 | 2 分钟内依赖它的服务没有新的错误告警；订单服务有成功事件 |
| `kill_transaction` | `trx_id`、`thread_id` | 这个事务还在、线程 id 相同、已持续 ≥ 10 s | `docker exec <mysql> mysql -e "KILL <thread_id>"`（两个参数只接受数字） | 事务是否还在 | 库存服务没有新的锁等待错误 |
| `set_switch` | `name`：`payment.fast-fail`、`inventory.fast-fail`；`on`；`minutes`（1–60） | 开关存在 | 订单服务管理端口 `POST /actuator/switches` | 开关状态 | 订单服务不再有超时；错误按"止血"而不是"恢复"判断 |

和设计（§5.3）的差异：结束事务不在库存服务里加接口，因为服务账号没有 `PROCESS` 权限、看不到别的连接的事务；由适配器用 MySQL 的管理账号经 `docker exec` 执行固定的 SQL。降级开关只做支付和库存两个，暂停抢购以后再加。

**只读探针（L0，给编排和模型）：** `GET /api/v1/lab/containers/{name}`、`GET /api/v1/lab/transactions`（持续 ≥ 5 s 的 InnoDB 事务：id、线程、时长、阻塞了几个）、`GET /api/v1/lab/switches`。

**验证任务：**

- `POST /api/v1/verifications/claim {worker}`：领取一个到期的（`pending` 且过了 `not_before`，或 `leased` 但租约已过期），`token + 1`，租约 60 s；没有就 204。
- `POST /api/v1/verifications/{id}/result {token, outcome, criteria}`：`token` 不是当前的、或任务已不是 `leased`，返回 409 `STALE_TOKEN`；任务已 `stale` 时结果照存但不改变处置单。`outcome`：`recovered`（处置单 `→ monitoring`）、`not_recovered`（`→ diagnosing`）、`inconclusive`（留在 `verifying`，等人看）。
- 判断在 Agent 的 `ops-agent verify` 进程里，全部是确定的检查（告警、日志计数），不用大模型。

### 13.3 靶场：降级开关

order-service 管理端口 `/actuator/switches`：`GET` 列出，`POST {"name","on","minutes"}` 打开或关闭。打开 `payment.fast-fail` 时扣款请求不发出，按"没有扣款"处理（订单取消）；`inventory.fast-fail` 时预留请求不发出。只拦截发起调用（扣款、预留），不拦截作废、释放、确认，保证账目仍然闭合。到期自动关闭；变化记在 `shop.switches` logger 下，这个 logger **要采集**（开关是处置的一部分）。

### 13.4 Agent

| 部分 | 实现 |
|---|---|
| 运行记录 | SQLite `run` 表：`run_id`、`session_id`、`request_id`（与会话一起唯一）、`trigger`、`status`、`message_id`、`case_id`、`snapshot`、`snapshot_digest`、时间。重复的 `request_id`：已结束的重放存下的回答（SSE，`done.replayed = true`），还在运行的返回 409 `RUN_IN_PROGRESS` |
| 页面上下文 | `page.incident_id`：问题里没有 Incident 编号、没有说组件和时间时，作为定位的依据（`kind = page`）。`case_id`、`case_version` 已在请求里，比较和提示还没做 |
| 处置单 | 诊断类问题定位到 Incident 后，编排打开（或取回）处置单；回答问卷、确认卡片时编排写入 `decision`；Runbook 探针的结果写入 `observation`/`fact`。模型的 `update_case` 工具只能写 `hypothesis`、`statement`、`constraint`，后两者的 `quote` 必须是用户这一轮或上文里的原话（逐字核对） |
| Runbook | `agent/src/ops_agent/runbooks/*.yaml`，四个（`mysql-unavailable`、`row-lock-wait`、`payment-degraded`、`inventory-degraded`）；按样本日志、告警和服务自己的警告错误里的特征匹配（必须有特征命中，规则的指向只加分）；`probes` 由编排执行，结果有编号（P1…） |
| 缺口 | `chat/gaps.py`：从定位结果、处置单、Runbook 和探针结果算出缺口和允许的下一步；`propose_action` 按它检查 |
| 处置卡片 | 新增 `start_container`、`kill_transaction`、`set_switch`；卡片带前置条件的观察、预期效果、影响范围、回退、验证计划和 `payload_digest`；确认时页面带回摘要 |
| 没有大模型时 | Runbook 匹配、前置条件满足时，编排直接提出 Runbook 的第一个处置（也是 §9.4 的对照组） |
| 验证 | `ops-agent verify`：领取验证任务，按标准查告警和日志计数，提交结果 |
| 结果评测 | `ops-agent eval-outcome`：经演练控制台注入故障，脚本化值班人员提问、按固定策略确认卡片，等验证结论，按演练控制台的标准答案打分 |

### 13.5 本轮不做

§7 推荐处理人和 §8 主动推进（G）留到下一轮；复盘草稿只在页面上按处置单拼出来，不单独存。

---

## 14. 进度与验证（2026-11-01）

### 14.1 已实现

| 阶段 | 实现 | 测试 |
|---|---|---|
| A 运行与请求 | SQLite `run` 表；`request_id` 重复时重放存下的回答（`done.replayed`），还在运行返回 409 `RUN_IN_PROGRESS`；运行结束状态（answered、paused_question、paused_action、degraded、failed、cancelled）与处置单状态分开；每轮存上下文快照及其摘要；页面上下文 `page.incident_id` | `test_chat_casework.py` |
| B 处置单 | core-service `casework`（V7）：§13.1 的表、条目来源约束、`version` 与 `state_version`、只有人能标为已解决；Agent 的 `CaseDesk`：编排写探针事实（新值取代旧值）、问卷的决定；模型的 `update_case` 只能写假设、用户的情况和限制，引用必须逐字出现在用户说过的话里 | `CaseworkIT`；Python 侧覆盖模型写事实被拒、引用不是原话被拒 |
| C 缺口与 Runbook | 4 个 Runbook；匹配必须有特征命中；探针由编排执行，结果编号 P1…；`gaps.py`；提出处置时检查缺口、Runbook 是否列出、前置条件、参数（结束事务的参数必须是探针看到的那个事务）、用户的限制 | 同上 |
| D 处置操作 | core-service `remediation`（V8）：摘要核对、`state_version` 核对、`action_id` 唯一、执行前再核对靶场、读回定回执；`DockerLabOperator`（`docker start`、`docker exec mysql KILL`、订单服务开关）；靶场的降级开关；卡片显示前置条件、效果、影响、回退、验证计划和摘要，确认时带摘要 | `CaseworkIT`、`DigestTest`（与 Python 的摘要逐字一致）、`OrderIT`、`SwitchesTest` |
| E 验证与验收 | 验证任务（租约 60 s、令牌、新处置让旧验证作废）；`ops-agent verify` 全部用确定的检查，没有数据时记为“证据不足”而不是通过；验证只把处置单推到 `monitoring`，验收按钮在页面上；复盘草稿由页面按处置单拼出 | `CaseworkIT`（两个工作进程抢同一个验证）、Python 侧判定 |
| F 结果评测 | `ops-agent eval-outcome`；没有大模型时按 Runbook 自动提出第一个可用的处置，作为对照组 | 见 §14.2 |

测试：core-service 45 个（含新增 6 个），order-service 新增 4 个，Agent 167 个（新增 15 个），前端 28 个（新增 2 个）；类型检查和构建通过。

### 14.2 结果评测：只用 Runbook 的对照组

2026-11-01，开发服务器，`java tools/Demo.java --baseline`（不用大模型），每秒 5 单的正常流量，每个场景 1 次（`runs/t2-outcome-baseline.json`）。脚本化值班人员在 Incident 出现后问“下单失败了，帮我处理一下”（页面上下文是这个 Incident），确认带前置条件的处置卡片，场景结束时验收并解决 Incident。

| 场景 | 检出 | 提出卡片 | 执行 | 处置 | 验证（窗口约 2 分钟） |
|---|---|---|---|---|---|
| `mysql-down` | 1.6 s | 4 s | 5 s | 启动 shop-mysql（读回 running） | 恢复：没有新告警；`order_paid` 361 次 |
| `hot-row-lock` | 9 s | 12 s | 13 s | 结束事务 3570778（线程 21，读回已不存在） | 恢复：处置前 6 行 `Lock wait timeout`，之后 0 行；`order_paid` 378 次 |
| `payment-errors` | 10 s | 12 s | 12 s | 打开 `payment.fast-fail`（读回已打开） | 止血生效：处置前 10 行支付调用失败，之后 0 行；`order_degraded` 394 次（订单直接取消，不算修复） |

3 个场景都对准了标准答案的目标组件，不必要的处置 0 次。时间是从注入算起；“验证”这一列约 130 s，主要是验证窗口本身的长度，处置真正生效的时间看“执行”一列。脚本化的值班人员立即确认，真实场景里还要加上人看卡片的时间。

**评测暴露、随后修正的问题（同一天三轮）：**

1. **验证把故障本身的告警算到了处置之后：** 第一轮 `mysql-down` 订单已经恢复（`order_paid` 362 次），验证却判“未恢复”，原因是 3 条新告警。查下来是统计基线检测（project-design §8.8）按分钟判断，16:33 这一分钟（数据库停着）在 16:34:00 结束时才发出告警，告警时间落在处置之后，说的却是处置之前。改为只算观察窗口在处置之后开始的告警（告警的 `window_start`），并加了测试。
2. **评测没有收尾，后面的故障并进了第一个 Incident：** 第一轮之后 MySQL 的 Incident 没有解决，3 分钟内的行锁和支付告警被关联进去，评测找不到新的 Incident。改为每个场景结束时由脚本化值班人员验收处置单并解决 Incident，这也是真实流程的最后一步。
3. **页面上下文的规则太窄：** 第二轮 `hot-row-lock` 没有提出处置。“下单失败了”里的“下单”被识别成 order-service，原规则是“说了组件就不用页面上的 Incident”，于是按时间窗口找到了几个指向不同组件的 Incident，对象不确定、不开处置单。改为提到的组件属于页面上那个 Incident（服务、出问题的服务、共同依赖）时仍以页面为准，并加了测试。

**更正（2026-11-04，见 §15.4）：** 上表 `hot-row-lock` 选对“结束事务”是碰巧。这个 Runbook 靠日志里的 `Lock wait timeout` 匹配，日志先到才选得对；重复 4 轮有 2 轮选成了止血开关。单次结果不能说明它稳定。

### 14.3 还没做

- **大模型版本的结果评测：** DeepSeek 余额不足（HTTP 402），没有跑。大模型版本走同样的约束（缺口、前置条件、参数、摘要），对照的意义在多个故障交织、需要核实用户说法和需要升级的场景，这几类场景还没写进 `eval-outcome`。
- **§7 推荐处理人、§8 主动推进**（阶段 G）。
- **页面上下文的版本比较：** `case_version` 已经在请求里，和服务端不一致时的提示还没做。
- **每个场景只跑了 1 次**；靶场之外的环境没有执行器，也不应该有，§11。

---

## 15. 运行时：每轮的用量和整条链路

§3 让每轮有了运行记录，但还回答不了两个问题：这一轮花了多少、时间花在哪；一次处置是哪一轮、依据哪份快照提出的。这一节只补这两件事。

### 15.1 每轮的账

`chat/usage.py`：

- **`Ledger`：** 一轮一本，记两类时间段。`model`：每次模型调用的层级（main、quick）、用途（route、answer）、开始时刻、用时、首个 token 的时间、token 数、结果（ok、error、cancelled）。`step`：图里每一步，就是已有的 `step()`，补上开始时刻。
- **`Metered`：** 包住模型，一轮一个，图和路由都用它，业务代码不用自己记。
- **存放：** `run.usage`（SQLite，旧库启动时补这一列）存完整的账；消息 trace 的 `metrics` 和 `done` 事件带合计，不带明细。

**如实记录：**

- 取消或失败的调用也记一段；服务商没报用量的调用计入 `calls_without_usage`，页面显示“至少 N tokens”，不把“没报”算成 0。
- 一轮被中断时，还在进行的时间段记为 `cancelled`。
- SDK 内部的重试（`max_retries=1`）看不到，只算一次调用。

**不记：** 会话摘要在回答结束后才在后台生成，不属于任何一轮；后台分析 Agent（`ops-agent run`）也不在内。金额不算，价格会变，这里只存 token。

### 15.2 链路

一条处置从提问到结论：

```
request_id ─ run ─ snapshot_digest ─ action（卡片）─ 人确认 ─ remediation ─ verification
              └─ case_entries（这一轮写的事实、假设、决定）
```

- **卡片到运行：** 不加字段。`action.message_id` 就是运行的 `message_id`，同一件事不存两份。提出相同卡片时返回已有的那张，所以卡片归最先提出它的那一轮，这也是事实。
- **运行到 core-service（V9）：** `remediations` 加 `run_id`、`session_id`、`snapshot_digest`，`verifications` 加 `run_id`（创建时从处置复制，工作进程领取时就能看到），`case_entries` 加 `run_id`。core-service 只保存这些值，不用它们做判断，也不进入 `payload_digest`：摘要说的是要做什么，不是谁提出的。这些列都可为空：不经过值班助手的调用没有运行。
- **快照摘要：** 运行结束时才算出。卡片在回答结束前就被确认时，`remediations.snapshot_digest` 为空，要从运行记录查。

**查询：** `GET /api/v1/chat/runs/{run_id}` 返回运行（含用量和快照）、问题和回答、这一轮的卡片及回执、每张卡片对应的处置和验证、这一轮写的处置单条目。core-service 不可用时，返回值班助手这边已有的部分和 `core_error`，不报错。页面 `/runs/:id`；回答底部和处置单里的处置都能跳过去。

**验证：** `eval-outcome` 每个场景结束后查这个接口，检查 `run_id` 从卡片一路传到了处置和验证（`chain_complete`）。

### 15.3 不做

OpenTelemetry（单机、3 个进程，一个 `run_id` 就够；要接时 `Ledger` 的时间段可以直接转成 span）；限流和配额（没有多用户，身份在 P5）；中断后接着跑（要先把图的每一步做成可重入，收益不够）；core-service 日志按 `run_id` 串联（处置的日志带 `run_id`，其余读请求不带）。

### 15.4 上线验证（2026-11-04）

**这一节的结论来自一次事故。** 等待脚本写错（`exec ... | tail` 在管道的子 shell 里执行，外层循环没有退出），本该跑 1 轮的 `eval-outcome` 连跑了约 97 分钟，共 11 轮，最后因为服务器内存不足被系统停掉。JSON 每轮被覆盖，逐轮结果只剩文字输出（`runs/t3-outcome-rounds-raw.txt`，未入库）。意外的好处是单次看不出的不稳定都暴露了。

前 4 轮有效（`--baseline`，不用大模型，每秒 5 单）：

| 场景 | 处置 | 验证 | 说明 |
|---|---|---|---|
| `mysql-down` | 4 轮都是启动容器 | 恢复 3，未恢复 1 | 未恢复那轮：重启后 `Order cancelled` 仍有 96 条，落进了验证窗口，同窗口 `order_paid` 361 次 |
| `hot-row-lock` | 结束事务 2，止血开关 2 | 恢复 2，止血 2 | 见下面第 1 条 |
| `payment-errors` | 止血开关 3，无卡片 1 | 止血 3 | 无卡片那轮：评测把上一个故障的余波（注入前 30 s 开的事件）当成了这次的检出 |

提出卡片的 11 轮，`run_id` 都从卡片传到了处置和验证（`chain_complete` 11/11），core-service 和 verify 的日志也都带 `run_id`。

第 5–11 轮全部“检出 否”，这是评测判错了，不是系统没检出：14:38 开的一个事件没人关，63 分钟里并入了 138 条告警，而评测只认注入之后新开的事件。

**查出、已修的问题：**

1. **行锁的交接（`defer`）在演示环境里从未生效。** 判断依据是 `blocking`，取自 `performance_schema.data_lock_waits`，而靶场的 MySQL 为省内存关了 Performance Schema（`deploy/shop/compose.yaml`），这张表永远是空的。单元测试用的假数据写了 `blocking: 3`，所以一直是绿的。两轮选成止血开关时，探针记录都是“持续 1 秒以上的事务：没有”。
   - 没有打开 Performance Schema（约 200 MB，这台机器内存本来就紧）。改用不依赖它的 `information_schema.innodb_trx.trx_state`：有事务处于 `LOCK WAIT` 就交接给行锁 Runbook。
   - 代价：关着 Performance Schema 时**只知道有人在等锁，不知道谁挡了谁**，选“没在等锁的事务里最老的”是推断，不是证据。所以卡片要如实写。
2. **确认结束事务前，人看到的是“阻塞 0 个”。** 这个 0 其实是“不知道”。现在 `blocking` 读不到时为空，卡片和前置检查都写“阻塞数未知”，打开 Performance Schema 后自动显示真实数字。
3. **可能结束错事务。** 原来按“最老”选，一个自己在等锁的事务也可能被选中，结束它什么锁也释放不了。现在 Runbook 选的时候排除，core-service 的前置检查也会拒绝（这一层以前没有测试）。
4. **评测本身：** 注入前先清场（关掉开着的事件，等 90 s 没有告警，比告警的 1 分钟窗口长；等不到就在结果里标明这一轮不干净）；只认注入之后开的事件；故障被并进老事件时明确报告，不再显示成没检出；加 `--rounds`，按场景统计；每个场景结束就写一次结果。

**验证到什么程度：**

| 内容 | 怎么验证的 |
|---|---|
| 用量和耗时的累计 | 假模型和模拟 DeepSeek 响应（httpx.MockTransport）；**没有对真实 API 跑过**，这一版也没有在线模型调用 |
| `run_id` 链路 | 真实环境 11 轮；V9 迁移和接口也有集成测试 |
| 新的事务查询 SQL | 在真实 MySQL 上用两个冲突的会话跑了 Java 里的原文：持锁者 `RUNNING`、等锁者 `LOCK WAIT`、阻塞数 `-1`（未知） |
| 修复后的交接和评测 | 单元测试和集成测试（agent 192、core-service 47，全部通过）；**修复后没有再跑真实环境的评测**，hot-row-lock 是否不再选止血开关还没有实测 |
| 运行详情页 `/runs/:id` | 只有构建和单元测试，**没有在浏览器里看过** |

**发现了、没有改：**

- **开着的事件会一直吸收新告警：** 没人处理时，一个事件开了 63 分钟，把几种不相干的故障并在一起。评测已经绕开，产品本身没改。
- **`mysql-down` 的“未恢复”偏保守：** 系统实际恢复了，只是重启初期的取消订单落进了窗口。方向是安全的（多让人看一眼），先不放宽。
- **有 `LOCK WAIT` 就交接可能误判：** 正常的短暂锁竞争超过 1 秒也会触发。秒杀流量下还没试过。
- 开关还开着时，处置单也能被验收。

