# 告警修复编排器（本地可运行）

一套**只依赖本地资源**的告警自动修复编排能力：按告警内容匹配修复策略并生成任务，
在重复告警、维护窗口、人工审批、执行失败与进程重启等情况下，让修复动作的最终结果
**可预期、可追溯**。无任何外部服务、无真实凭据；存储只用本地 JSON 文件，动作用内置
无副作用实现，开箱即可运行与验证。

## 1. 核心能力

- **策略匹配 + 任务生成**：按 `target 前缀 / type / content 关键字` 匹配，优先级确定
  （priority 升序，相同按 id 字典序），命中策略即生成修复任务。
- **同目标串行、全局限流排队不丢弃**：同一 `target` 的任务严格串行；全局并发超过
  `remediation.global-concurrency` 时进入 FIFO 队列等待，绝不丢弃。
- **重复告警去重**：去重键 = `SHA-256(target | type | 规范化 content)`（content 做 trim
  与空白折叠）。命中进行中任务 → 合并计数（`DUPLICATE_MERGED`）；命中已终态任务 →
  不再修复（`DUPLICATE_TERMINAL`）。
- **维护窗口抑制**：窗口内告警被抑制（`SUPPRESSED_MAINTENANCE`），**不建任务、不执行
  任何动作、不产生修复副作用**，但有审计记录。
- **人工审批（高风险）**：`HIGH` 策略在执行前必须批准；批准前动作绝不执行。四种结局
  彼此可区分且都是终态。
- **失败重试与超时**：步骤串行执行，单步有最大尝试次数与固定退避，单次执行有超时；
  最终失败记录足够定位信息（步骤名、尝试次数、末次错误）。
- **崩溃恢复**：状态全部持久化，重启后未完成任务自动恢复，**已完成步骤绝不重复执行**。
- **全程审计**：受理判定、状态迁移、审批、恢复全部写入只追加的审计日志并打印 `[AUDIT]`。

## 2. 状态模型

### 2.1 任务状态机

合法迁移由聚合根 `RemediationTask` 强约束，非法迁移抛 `IllegalStateException`；
每次迁移 `version` 自增并原子落盘。

```
PENDING ──> QUEUED ──> RUNNING ──> SUCCEEDED
              │          │
              │          ├──> FAILED
              │          └──> WAITING_APPROVAL ──> QUEUED        (批准后重排执行)
              │                                    └──> APPROVAL_REJECTED
              │                                    └──> APPROVAL_TIMEOUT
              └────────────────────────────────────└──> CANCELLED
```

五个可区分终态：`SUCCEEDED / FAILED / APPROVAL_REJECTED / APPROVAL_TIMEOUT / CANCELLED`。

> 说明：高风险任务在**执行前**从 `QUEUED` 进入 `WAITING_APPROVAL`，审批等待**不占用
> 全局并发名额**；批准后重新入队执行。低风险任务直接 `QUEUED → RUNNING`。

### 2.2 步骤状态

`PENDING → RUNNING → SUCCEEDED | FAILED`。只有 `SUCCEEDED` 是终态。崩溃恢复时，
任何非 `SUCCEEDED` 的步骤连同尝试次数、末次错误一起重置为 `PENDING`，因此：

- 已 `SUCCEEDED` 的步骤恢复后**直接跳过，绝不重复执行**；
- 中断时进行中的步骤恢复后**获得完整重试预算**，从头执行。

### 2.3 告警受理结论（可区分原因）

`ACCEPTED`、`DUPLICATE_MERGED`、`DUPLICATE_TERMINAL`、`SUPPRESSED_MAINTENANCE`、
`REJECTED_NO_STRATEGY`，每种都有独立审计事件。

## 3. 持久化与恢复语义

- 数据目录：`remediation.data-dir`（默认 `data/`）。
- 任务：`data/tasks/<taskId>.json` 快照，写入采用 **临时文件 + 原子 rename**，崩溃不会
  留下半写入文件。并发保存时与磁盘最新快照合并运行态字段，避免旧内存对象覆盖
  并发累加的“重复告警计数”。
- 审计：`data/audit.log`，**JSON Lines 只追加**，`seq` 全局单调递增（启动时按历史
  最大值续号）。
- 启动恢复（`remediation.recover-on-startup=true`）扫描所有非终态任务：
  - `RUNNING`：重置未完成步骤后重新排队，已成功步骤保留；
  - `QUEUED`：直接重新入队；`PENDING`：迁移到 `QUEUED` 后入队；
  - `WAITING_APPROVAL`：按原 `deadline` 重新登记审批超时定时器。
- 工作线程在执行期间被中断（模拟进程被杀）时**不做终态迁移**，保留 `RUNNING` 快照
  供重启恢复；每个修复步骤在**全新守护线程**中执行、用后即弃，避免复用带中断标志的线程。

## 4. 可配置项

`src/main/resources/application.properties`，前缀 `remediation.`：

| 键 | 默认 | 含义 |
|---|---|---|
| `global-concurrency` | `2` | 全局并发上限，超限任务排队不丢弃 |
| `approval-timeout-ms` | `60000` | 高风险审批超时（毫秒），超时为独立终态 |
| `data-dir` | `data` | 本地持久化目录（任务快照 + 审计日志） |
| `recover-on-startup` | `true` | 启动时是否自动恢复未完成任务 |

策略级参数在策略定义中：`maxAttempts`（每步最大尝试次数，含首次）、
`retryBackoffMs`（固定退避）、步骤 `timeoutMs`（单次执行超时）、`riskLevel`。

内置两条本地演示策略（`EngineConfiguration`）：

- `disk-full`（低风险）：`cleanup-logs` → `verify-space` 两步，匹配 DISK_FULL 关键字；
- `service-down`（高风险）：`restart-service` 单步，需审批，匹配 SERVICE_DOWN 关键字。

实现自定义动作：实现 `RemediationAction`（`execute` 返回 `ActionResult`，只依赖本地
资源、尽量幂等），注册为 Spring bean，bean 名称即策略步骤里的 `actionName`。

## 5. 本地验证方法

需要 JDK 21+、Maven 3.9+（仅用仓库已有的 spring-boot-starter-web/test 依赖）。

### 5.1 运行测试

```bash
mvn clean test
```

### 5.2 启动应用

```bash
mvn -DskipTests package
java -jar target/remediation-orchestrator-java-1.0-SNAPSHOT.jar
# 或 mvn spring-boot:run
```

### 5.3 手工 HTTP 验证（端口 8080）

```bash
# 低风险告警 -> 自动执行成功
curl -s -X POST localhost:8080/api/alerts -H 'Content-Type: application/json' \
  -d '{"target":"host-1","type":"DISK_FULL","content":"disk full at /var"}'

# 高风险告警 -> WAITING_APPROVAL（动作不执行）
curl -s -X POST localhost:8080/api/alerts -H 'Content-Type: application/json' \
  -d '{"target":"svc-1","type":"SERVICE_DOWN","content":"service down"}'
# 取 taskId 后：批准 / 拒绝 / 取消（三种可区分终态）
curl -s -X POST localhost:8080/api/tasks/<taskId>/approve
curl -s -X POST localhost:8080/api/tasks/<taskId>/reject
curl -s -X POST localhost:8080/api/tasks/<taskId>/cancel

# 登记维护窗口（ISO-8601 时间），窗口内目标告警被抑制
curl -s -X POST localhost:8080/api/maintenance-windows -H 'Content-Type: application/json' \
  -d '{"id":"w1","targetPrefix":"host-","start":"2026-09-23T00:00:00Z","end":"2026-09-24T00:00:00Z"}'

# 查询任务与审计
curl -s localhost:8080/api/tasks
curl -s localhost:8080/api/tasks/<taskId>
curl -s localhost:8080/api/tasks/<taskId>/audit
curl -s localhost:8080/api/audit
```

删除 `data/` 目录即可在干净状态重新开始；保留 `data/` 重启会自动恢复未完成任务。

## 6. 测试覆盖

测试不启动外部服务，用 `support/RecordingAction`（可阻塞、可注入失败、按任务计数）与
`support/TestEngine`（手工装配、同一数据目录 `restart()` 模拟进程重启、每实例独立
UUID 子目录隔离）。日志统一打印 `[TEST] INPUT / DECISION / ASSERT`，主代码打印
`[INTAKE]/[MATCH]/[DISPATCH]/[EXEC]/[APPROVAL]/[RECOVER]/[AUDIT]` 的输入、判定依据与
关键状态迁移。

| 测试类 | 覆盖场景 |
|---|---|
| `DeduplicationAndRetryTest` | 等价告警合并/终态去重、修复只执行一次；重试上限后失败且信息可定位 |
| `ConcurrencyLimitTest` | 全局并发超限排队不丢弃；同目标严格串行 |
| `MaintenanceWindowTest` | 窗口内抑制、无副作用；窗口外正常受理 |
| `ApprovalInterceptTest` | 批准前不执行；批准/拒绝/超时/取消四态可区分；排队中取消不执行 |
| `RestartRecoveryTest` | RUNNING 中断后重启恢复，已完成步骤不重复执行 |
| `ApprovalRecoveryTest` | WAITING_APPROVAL 期间重启，恢复后仍需审批、批准后完成 |
| `StepTimeoutTest` | 单步超时、按上限重试、最终失败与 STEP_TIMEOUT 审计；无策略拒绝 |

断言最终任务状态（五个终态）与审计结论（`TASK_RECOVERED / STEP_TIMEOUT /
APPROVAL_* / TASK_CANCELLED / ALERT_*` 等），保证“任务状态与审计记录自洽”。

## 7. 代码结构

```
model/     告警、策略、步骤、任务聚合根（状态机）、审计事件与枚举
action/    RemediationAction SPI、ActionResult、内置本地动作（builtin/NoopAction）
store/     TaskStore/AuditStore 接口 + JSON/JSONL 本地实现（原子写、只追加）
engine/    受理门面、去重、维护窗口、策略匹配、调度、审批、执行、恢复、审计
config/    可配置项、Spring 装配、生命周期（启动恢复/优雅停止）
web/       REST 控制器（受理、审批、窗口、查询）
```
