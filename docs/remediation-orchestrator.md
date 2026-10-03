# 告警修复编排（Remediation Orchestrator）

一套完全本地化的告警修复编排能力：按告警内容匹配修复策略并生成修复任务，
在**重复告警、维护窗口、人工审批、执行失败与进程重启**等情况下，让修复动作的
最终结果仍然**可预期、可追溯**。不接入任何外部服务，不使用真实凭据。

- 运行形态：Spring Boot 内嵌 Web，本地文件持久化（JSON / JSONL）。
- 本地动作：仅向本地文件追加标记、空操作、可控失败/慢动作，便于断言与演示。
- 依赖：仅使用仓库已有依赖（Spring Web、Jackson、SLF4J、JUnit 5）。

---

## 1. 总体流程

```
HTTP 告警 ──▶ TaskEngine.accept
                │
                ├─ 1. 策略匹配（关键字 + 最低严重级别，按序取第一条启用策略）
                │      └─ 无命中 → REJECTED_NO_POLICY（审计，不建任务）
                ├─ 2. 维护窗口判定（目标精确匹配或 "*"，左闭右开区间）
                │      └─ 命中 → SUPPRESSED_MAINTENANCE（审计，不建任务，无副作用）
                ├─ 3. 去重指纹 = sha256(policyId | target | 规范化正文) 前 16 位
                │      └─ 存在同指纹未终结任务 → DUPLICATE（返回既有任务 ID 与原因）
                ├─ 3.5 复发判定：同指纹最近任务已终结（见第 5 节）
                │      ├─ 人工放弃期内 → RECURRENCE_ABANDONED
                │      ├─ 有待消费的人工确认 → 消费并放行一次新任务（CREATED）
                │      ├─ 统计窗内复发次数 ≥ 阈值 → RECURRENCE_ESCALATED
                │      ├─ 前次成功且抑制窗口内 → RECURRENCE_SUPPRESSED
                │      ├─ 前次成功且窗口已过 → 放行一次新任务（CREATED）
                │      └─ 前次未成功 → RECURRENCE_AFTER_FAILURE
                └─ 4. 创建任务
                       ├─ 策略需审批 → WAITING_APPROVAL（ApprovalService 登记截止时间）
                       └─ 否则 → QUEUED

TaskDispatcher（定时扫描 + 固定大小线程池）
   ├─ 审批超时扫描（WAITING_APPROVAL 超过截止 → APPROVAL_TIMEOUT 终态）
   ├─ 取 QUEUED / 到期的 RETRY_WAIT 任务：
   │     · 全局在跑任务数 < max-concurrent，否则保持排队（不丢弃）
   │     · 同一 target 同时只有一个任务在跑（串行）
   ├─ 逐步执行：已 SUCCEEDED 的步骤跳过（重启后也不会重跑）
   │     · 失败且仍有重试额度 → RETRY_WAIT + nextAttemptAt，线性退避 base*n
   │     · 超过重试额度 → FAILED（终态原因含步骤、尝试次数、底层错误）
   │     · 步骤超时/取消 → 中断工作线程
   └─ 全部步骤成功 → SUCCEEDED
```

受理结论 `DispositionCode`（被抑制/合并/拒绝原因彼此可区分）：

| 结论 | 含义 | 是否建任务 |
|---|---|---|
| `CREATED` | 新建修复任务（可能立即排队，也可能等待审批；含复发被放行/确认后重开） | 是 |
| `DUPLICATE` | 等价告警已存在未终结任务，返回既有任务 ID 与当前状态 | 否（合并） |
| `SUPPRESSED_MAINTENANCE` | 目标处于维护窗口，抑制且无任何修复副作用 | 否 |
| `REJECTED_NO_POLICY` | 无启用策略命中（含策略被禁用） | 否 |
| `RECURRENCE_SUPPRESSED` | 前次修复成功后，复发处于抑制窗口内 | 否 |
| `RECURRENCE_ESCALATED` | 统计窗内复发次数达阈值，升级为需人工确认 | 否 |
| `RECURRENCE_AFTER_FAILURE` | 前次修复未成功（失败/取消/审批拒绝/超时），复发需人工确认 | 否 |
| `RECURRENCE_ABANDONED` | 人工已放弃该目标本次修复，统计窗内不再自动修复 | 否 |

---

## 2. 状态模型

### 2.1 任务状态 `TaskState`

| 状态 | 含义 | 后继 |
|---|---|---|
| `QUEUED` | 已受理，等待调度槽位/审批放行 | `RUNNING` / `WAITING_APPROVAL`(仅创建时) / `CANCELLED` |
| `WAITING_APPROVAL` | 高风险动作等待人工审批 | `QUEUED`(批准) / `APPROVAL_REJECTED` / `APPROVAL_TIMEOUT` / `CANCELLED` |
| `RUNNING` | 有步骤正在执行 | `RETRY_WAIT` / `SUCCEEDED` / `FAILED` / `CANCELLED` |
| `RETRY_WAIT` | 步骤失败后退避中，`nextAttemptAt` 到期可再调度 | `RUNNING` / `CANCELLED` |
| `SUCCEEDED` | 终态：全部步骤成功 | — |
| `FAILED` | 终态：步骤重试耗尽（含超时） | — |
| `APPROVAL_REJECTED` | 终态：审批被拒绝 | — |
| `APPROVAL_TIMEOUT` | 终态：审批超时（停机期间超时也会在恢复时落此态） | — |
| `CANCELLED` | 终态：人工主动取消（审批中/排队中/运行中均可取消） | — |

### 2.2 审批状态 `ApprovalStatus`

`NONE → PENDING → APPROVED | REJECTED | TIMED_OUT | CANCELLED`

四种结局**彼此可区分**，且分别对应任务的不同终态/后继：
批准可继续执行；拒绝、超时为终态；取消伴随任务 `CANCELLED`。

### 2.3 步骤状态 `StepState`

`PENDING → RUNNING → SUCCEEDED | FAILED | CANCELLED`（另有 `SKIPPED` 保留）。
取消时所有未成功步骤收束为 `CANCELLED`，系统中不存在“停在 RUNNING 的步骤 + 已终结任务”
这种不自洽组合（唯一例外：进程被杀死，此时任务整体保持 `RUNNING` 等待恢复，见第 4 节）。

### 2.4 自洽性保证

- 每次状态迁移都遵循“**先写任务 JSON（临时文件 + 原子 rename），再追加审计 JSONL**”，
  审计只增不改；任意终态都可在任务记录与审计流中找到对应事件。
- 失败终态原因格式：
  `step <i> (<name>) failed after <n> attempts: <最后一次底层错误>`，
  同时保留每个步骤的 `attempts` / `lastError`，足以定位原因。

---

## 3. 策略模型

策略位于 `src/main/resources/policies.json`（也可用文件路径覆盖，见配置项），
按数组顺序匹配**第一条**启用策略：

```json
{
  "id": "disk-full-cleanup",
  "matchAnyKeywords": ["disk full", "磁盘满"],
  "minSeverity": "WARN",
  "requiresApproval": false,
  "approvalTimeoutSeconds": 3600,
  "maxRetries": 2,
  "backoffBaseMillis": 50,
  "stepTimeoutMillis": 2000,
  "enabled": true,
  "steps": [
    { "type": "MARKER", "name": "mark", "params": { "message": "cleanup-started" } }
  ]
}
```

- `matchAnyKeywords`：告警正文（大小写不敏感）包含任一关键字即命中；**空数组表示通配**。
- `minSeverity`：`INFO < WARN < CRITICAL`，为空表示不限制。
- 重试次数/退避/步骤超时/审批超时均可按策略覆盖，留默认值（-1/0）则取全局配置。

内置的纯本地步骤类型：

| 类型 | 参数 | 行为 |
|---|---|---|
| `MARKER` | `message` | 向 `<data-dir>/markers/<target>.log` 追加一行，副作用可观测 |
| `NOOP` | — | 恒成功 |
| `FLAKY` | `failFirst`（默认 2） | 按“任务+步骤”在本地计数，前 n 次失败之后成功，用于验证重试 |
| `ALWAYS_FAIL` | `message` | 恒失败，用于验证失败终态与信息留存 |
| `SLOW` | `sleepMillis` | 睡眠，响应中断，用于验证步骤超时/取消/关停 |

---

## 4. 恢复语义（崩溃与重启）

调度器作为 Spring `SmartLifecycle` 启动，**先恢复扫描再开始调度**：

| 重启前状态 | 恢复动作 |
|---|---|
| `QUEUED` | 继续排队 |
| `RUNNING` | 崩溃时在跑的步骤若处于 RUNNING 则重置为 PENDING，任务回到 QUEUED；**已经 SUCCEEDED 的步骤原样保留，绝不重新执行** |
| `RETRY_WAIT` | 回到 QUEUED（退避重新计时），已持久化的 attempts 不丢 |
| `WAITING_APPROVAL` | 审批是外部决策，继续等待；若截止时间已过则落 `APPROVAL_TIMEOUT` |
| 非终结 + `cancelRequested` | 关停前已受理的主动取消没来得及收尾：恢复器直接收尾为 `CANCELLED`（取消优先） |
| 任何终态 | 完全不触碰 |

### 4.1 关停恰好落在“等待重试 / 排队”期间

优雅关停（`SmartLifecycle.stop`，非 `kill -9`）可能打断的不只是正在执行的步骤，
也可能是**退避等待重试**（`RETRY_WAIT`）或**排队等槽位**（`QUEUED`）中的任务。
这两类情形的语义与“执行中被打断”一致：

- **关停路径不写任何终态**。退避等待被关停中断时，任务保持已持久化的
  `RETRY_WAIT + nextAttemptAt`；排队中的任务本来就没有线程在跑，保持 `QUEUED`。
  重启后恢复器把它们重新纳入调度：该重试的继续重试、该排队的继续排队，
  最终正常走到 `SUCCEEDED` / `FAILED`。
- **已累计的尝试次数不清零**：重试预算（`maxRetries`）按持久化的 `attempts`
  跨重启连续计算，重启不会白送额外重试；被关停打断的那一次“未完成尝试”不占预算。
- **已成功步骤不重跑**：只有 `SUCCEEDED` 的步骤会产生对外可观测副作用（如 MARKER 追加），
  恢复后这些步骤直接跳过，副作用不会重复。

### 4.2 关停打断 ≠ 人工取消

| | 关停打断 | 人工取消 |
|---|---|---|
| 触发 | 进程优雅关停中断工作线程 | `POST /api/tasks/{id}/cancel` 置 `cancelRequested` |
| 落库结论 | 保持非终结状态（`RUNNING`/`RETRY_WAIT`/`QUEUED`），不写终态 | 收束为 `CANCELLED` 终态，未成功步骤置 `CANCELLED` |
| 重启后 | 恢复器重新排队，按原安排继续推进 | 终态不被触碰，绝不复活 |
| 审计 | `RECOVERY_RESUMED`（from=关停时状态） | `TASK_CANCELLED`（执行期 stage=execution；恢复器收尾 stage=recovery） |

**关停与取消几乎同时发生时，结论唯一、可判定**：以存储中的 `cancelRequested`
标志为唯一判定依据，取消优先。判定与写回在存储锁内原子完成
（`TaskStore.update`），因此：

- 取消标志先落库 → 工作线程/恢复器观察到标志，收尾为 `CANCELLED`；
- 关停先把工作线程打断 → 打断路径不写库，取消请求随后正常落库，
  恢复器启动时把带标志的任务收尾为 `CANCELLED`；
- 不存在“先被记成已结束、重启后又被接着执行”的顺序：打断路径不产生任何终态写，
  恢复器对终态任务一律不触碰。

### 4.3 持久化与幂等边界

- 持久化：`tasks/<task-id>.json`（原子写）+ `audit.jsonl`（只追加）+
  `recurrence.json`（复发事件与确认/放弃标记，原子写），
  因此“任务状态”“审计结论”和“复发计数”在重启后仍然自洽；
  停在 `QUEUED`/`RETRY_WAIT` 的修复不会被复发判定误当成已结束。
- 优雅关停时中断工作线程：任务保持非终结状态落库，下次启动按上表恢复；
  不会在关停路径写入任何虚假终态。
- 幂等边界：步骤在 SUCCEEDED 之后才产生对外可观测副作用（如 MARKER 追加成功），
  崩溃的当前步骤整体重跑；`RecoveryTest` 与 `ShutdownRecoveryTest` 用 marker 文件行数
  证明已完成步骤不重复。

---

## 5. 复发与升级（Recurrence & Escalation）

同一个目标、同类问题、内容等价的告警（同去重指纹）在**前次修复已经终结**之后再次到达时，
不再当作全新修复处理，而是进入复发路径。三种情形可明确区分：

| 情形 | 判定依据 | 结论 |
|---|---|---|
| 修复还在进行中的重复 | 同指纹存在未终结任务 | `DUPLICATE`（合并，不新增） |
| 刚修好又复发 | 同指纹最近终态任务为 `SUCCEEDED` | 抑制窗口内 `RECURRENCE_SUPPRESSED`；窗口过后放行一次新任务（`CREATED`） |
| 之前修复失败后又来一次 | 同指纹最近终态任务为 `FAILED`/`CANCELLED`/审批拒绝/审批超时 | `RECURRENCE_AFTER_FAILURE`（需人工确认） |

### 5.1 抑制窗口（recurrence-suppression-window-millis）

- 前次修复**成功终结**后，窗口内的复发一律不新增修复动作（`RECURRENCE_SUPPRESSED`）；
- 窗口届满（`now - finishedAt >= 窗口`，边界为左闭右开）**最多放行一次**新修复；
- 重开出来的任务走正常 `QUEUED` → 调度流程，**仍受同目标串行与全局并发上限约束**；
  该任务未终结前，后续等价告警按 `DUPLICATE` 合并；它终结后抑制窗口从其 `finishedAt` 重新起算，
  因此告警持续推送也不会把执行资源打满。

### 5.2 升级（统计窗 + 复发阈值）

- 每次复发（前次修复终结后的等价告警）都会按**目标**记录一条复发事件并持久化；
- 统计窗（`recurrence-stats-window-millis`）内事件数 ≥ `recurrence-escalation-threshold`
  即升级为 `RECURRENCE_ESCALATED`：人工确认之前**不得自动执行任何修复、无任何副作用**；
- 升级是**派生状态**：窗口滑动后旧事件过期、计数回落，升级自然解除，不会永久卡死；
- 人工确认（`POST /api/recurrence/{target}/confirm`）：清除已累计事件，
  下一次复发放行**一次**真实修复（消费确认后复位；绕过抑制窗口与失败后拦截，因为人工已明确表态）；
- 人工放弃（`POST /api/recurrence/{target}/abandon`）：清除已累计事件，
  之后一个统计窗内该目标的复发一律 `RECURRENCE_ABANDONED`，窗口滑过后恢复常规判定；
- 升级状态、当前复发次数与所依据的时间窗可通过
  `GET /api/recurrence/{target}`（或 `GET /api/recurrence` 全量）查询。

### 5.3 与既有语义的兼容边界

- **去重**：未终结任务（含 `QUEUED`/`RETRY_WAIT`/`RUNNING`/`WAITING_APPROVAL`）优先命中
  `DUPLICATE`，复发判定只针对“同指纹任务已全部终结”的情形；
- **维护窗口**：窗口判定在复发判定之前，命中窗口的告警既不计复发事件也不建任务；
- **审批**：复发被放行/确认后重开的任务，若策略要求审批仍进入 `WAITING_APPROVAL`，语义不变；
- **恢复**：复发事件、确认/放弃标记持久化在 `<data-dir>/recurrence.json`（原子写），
  重启后计数不清零；“前次修复结果”直接从任务存储派生，因此停在
  `QUEUED`/`RETRY_WAIT` 的未完成修复不会被误判为已结束，重启后仍按原安排恢复推进。

---

## 6. 可配置项

前缀 `remediation.orchestrator`（`application.properties`，均可用环境变量/命令行覆盖）：

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `data-dir` | `data/remediation` | 任务 JSON、审计 JSONL、marker、维护窗口文件的根目录 |
| `max-concurrent` | `4` | 全局同时执行的任务数上限；超出的任务排队而非丢弃 |
| `default-max-retries` | `2` | 默认步骤最大重试次数（不含首次执行） |
| `default-backoff-base-millis` | `200` | 线性退避基数，第 n 次重试等待 `base*n` 毫秒 |
| `default-step-timeout-millis` | `5000` | 单步骤超时；超时中断执行线程并按失败处理 |
| `default-approval-timeout-seconds` | `60` | 默认审批超时 |
| `tick-millis` | `50` | 调度扫描间隔 |
| `policies-location` | `classpath:policies.json` | 策略文件，支持 classpath: 与本地路径 |
| `maintenance-file` | `<data-dir>/maintenance.json` | 维护窗口持久化文件 |
| `recurrence-suppression-window-millis` | `300000` | 复发抑制窗口：前次修复成功后该窗口内的复发不新增修复动作 |
| `recurrence-stats-window-millis` | `3600000` | 复发统计时间窗（滑动）：升级判定与放弃期的依据 |
| `recurrence-escalation-threshold` | `3` | 统计窗内复发次数达到该阈值即升级为需人工确认 |

运行时还可通过 HTTP API 增删维护窗口（立即持久化）。

---

## 7. HTTP API（本地，无鉴权/无外部依赖）

| 方法与路径 | 说明 |
|---|---|
| `POST /api/alerts` | 提交告警，返回 disposition/taskId/reason |
| `POST /api/tasks/{id}/approve` | 批准审批，body `{"approver":"..."}` |
| `POST /api/tasks/{id}/reject` | 拒绝审批，body `{"approver":"...","reason":"..."}` |
| `POST /api/tasks/{id}/cancel` | 主动取消（审批中/排队中/运行中均可） |
| `GET  /api/tasks/{id}` | 查询任务完整状态（含步骤、尝试次数、错误） |
| `GET  /api/tasks` | 列出全部任务 |
| `GET  /api/audit?taskId=` | 查询审计事件（不带参为全部，JSONL 同源回读） |
| `GET/POST/DELETE /api/maintenance[/{id}]` | 维护窗口查询/新增/删除 |
| `GET  /api/recurrence` | 全部目标的复发/升级状态（计数、时间窗、确认/放弃标记） |
| `GET  /api/recurrence/{target}` | 单个目标的复发/升级状态 |
| `POST /api/recurrence/{target}/confirm` | 人工确认：放行该目标下一次复发的一次真实修复 |
| `POST /api/recurrence/{target}/abandon` | 人工放弃：统计窗内该目标复发不再自动修复 |

审批已决定/任务已终结时再次决策返回 `409`；任务不存在返回 `404`。

---

## 8. 本地验证方法

无需任何外部服务或凭据，JDK 21+ 与 Maven 即可。

### 8.1 运行全部测试（35 个用例）

```bash
mvn test
```

覆盖场景与对应用例：

| 场景 | 测试类 |
|---|---|
| 策略匹配与判定依据 | `PolicyMatcherTest` |
| 重复/等价告警去重、无策略拒绝 | `DuplicateAlertTest` |
| 维护窗口抑制（含通配窗口、文件持久化重载） | `MaintenanceWindowTest` |
| 同目标串行、超全局上限排队不丢弃 | `ConcurrencyTest` |
| 审批拦截：未批不执行、批准/拒绝/超时/取消四态区分 | `ApprovalTest` |
| 运行中/排队中取消，无半完成状态 | `CancelRunningTest` |
| 重试上限与退避、恒失败终态、步骤超时中断 | `RetryAndFailureTest` |
| 重启恢复、已完成步骤不重跑、停机期间审批超时 | `RecoveryTest` |
| 关停落在退避等待/排队期间：不写终态、重启续跑、取消优先 | `ShutdownRecoveryTest` |
| 复发抑制/受控重开、窗口边界、升级与确认、放弃、重启后复发连续性 | `RecurrenceTest` |
| HTTP 全链路（受理→去重→审批→成功→审计；窗口抑制） | `EndToEndIntegrationTest` |

单测日志会打印**输入、判定依据与关键状态迁移**，例如
`[accept] input ...`、`[match] ... basis: ...`、`[run] task=... step=... attempt=...`、
`[recovery] ... completed steps preserved: ...`。

### 8.2 复现“关停落在等待重试期间”的场景

`ShutdownRecoveryTest` 在本地真实复现该场景（真实文件存储 + 真实调度线程）：

```bash
mvn test -Dtest=ShutdownRecoveryTest
```

关键用例 `shutdownDuringBackoffKeepsTaskUnfinishedAndResumesAfterRestart` 的流程：

1. 策略为 `MARKER(必成功) → FLAKY(第 1 次失败)`，退避基数 30s，真实调度跑起来；
2. step0 成功、step1 第一次失败后任务进入 `RETRY_WAIT`（日志可见
   `关停前关键状态: state=RETRY_WAIT ... steps=[0:SUCCEEDED(attempts=1), 1:PENDING(attempts=1)]`）；
3. 退避等待期间调用 `dispatcher.stop()` 优雅关停，断言任务**仍是 `RETRY_WAIT` 非终态**、
   审计中无 `TASK_CANCELLED`；
4. 在同一数据目录上重新初始化存储与调度器（模拟重启），断言任务被恢复重排并推进到
   `SUCCEEDED`：step0 的 `attempts` 仍为 1 且 marker 行数为 1（副作用不重复），
   step1 的 `attempts` 累计为 2（尝试次数不清零）。

同类的 `queuedTaskSurvivesShutdownAndRunsAfterRestart` 覆盖“排队期间关停”，
`pendingCancelAtShutdownIsFinalizedOnRecovery` 覆盖“关停与取消同时发生时取消优先”。

### 8.3 启动应用并手工验证

```bash
mvn spring-boot:run
# 或
mvn package -DskipTests && java -jar target/remediation-orchestrator-java-1.0-SNAPSHOT.jar

# 1) 高危告警（默认策略 restart-risky-service 需要审批）
curl -s -X POST localhost:8080/api/alerts -H 'Content-Type: application/json' \
  -d '{"id":"a1","target":"db-1","content":"need restart","severity":"CRITICAL"}'
# 2) 批准后观察任务完成
curl -s -X POST localhost:8080/api/tasks/<taskId>/approve \
  -H 'Content-Type: application/json' -d '{"approver":"oncall"}'
curl -s localhost:8080/api/tasks/<taskId>
# 3) 审计链
curl -s "localhost:8080/api/audit?taskId=<taskId>"

# 4) 复发场景：同一告警在修复成功后 5 分钟（默认抑制窗）内再次推送
curl -s -X POST localhost:8080/api/alerts -H 'Content-Type: application/json' \
  -d '{"id":"a2","target":"db-1","content":"need restart","severity":"CRITICAL"}'
#    → RECURRENCE_SUPPRESSED / RECURRENCE_ESCALATED 等，不新增任务
# 5) 查看目标复发状态，人工确认后再推送即放行一次新修复
curl -s localhost:8080/api/recurrence/db-1
curl -s -X POST localhost:8080/api/recurrence/db-1/confirm \
  -H 'Content-Type: application/json' -d '{"approver":"oncall"}'
```

运行后可在 `data/remediation/` 下检查：

- `tasks/*.json`：任务与步骤的最终状态；
- `audit.jsonl`：每行一个审计事件；
- `markers/<target>.log`：MARKER 动作的本地副作用证据；
- `maintenance.json`：维护窗口；
- `recurrence.json`：各目标复发事件时间戳与人工确认/放弃标记。
