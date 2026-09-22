package com.github.xingjianluo.remediationorchestratorjava.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 修复任务聚合根。所有状态迁移只能通过这里的方法发生；每次迁移都会令 version 自增，
 * 配合存储层的原子快照写入，保证“任务状态与审计记录自洽”。
 */
public class RemediationTask {

    private String id;
    private String fingerprintKey;
    private String alertId;
    private String target;
    private String type;
    private String strategyId;
    private RiskLevel riskLevel;
    private int maxAttempts;
    private long retryBackoffMs;
    private TaskStatus status = TaskStatus.PENDING;
    private List<TaskStep> steps = new ArrayList<>();
    private int mergedDuplicateCount;
    private boolean approvalGranted;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant deadline;
    private String failureSummary;
    private long version;

    public RemediationTask() {
    }

    /** 由受理服务在匹配到策略后创建，初始状态 PENDING，步骤全部 PENDING。 */
    public static RemediationTask create(String id, String fingerprintKey, Alert alert,
                                        RemediationStrategy strategy, Instant now) {
        RemediationTask t = new RemediationTask();
        t.id = id;
        t.fingerprintKey = fingerprintKey;
        t.alertId = alert.alertId();
        t.target = alert.target();
        t.type = alert.type();
        t.strategyId = strategy.id();
        t.riskLevel = strategy.riskLevel();
        t.maxAttempts = strategy.maxAttempts();
        t.retryBackoffMs = strategy.retryBackoffMs();
        t.status = TaskStatus.PENDING;
        for (StepSpec spec : strategy.steps()) {
            TaskStep s = new TaskStep();
            s.setName(spec.name());
            s.setActionName(spec.actionName());
            s.setTimeoutMs(spec.timeoutMs());
            s.setParams(spec.params());
            t.steps.add(s);
        }
        t.createdAt = now;
        t.updatedAt = now;
        return t;
    }

    private RemediationTask touch(Instant now) {
        this.updatedAt = now;
        this.version++;
        return this;
    }

    private void require(TaskStatus expected) {
        if (status != expected) {
            throw new IllegalStateException(
                    "illegal transition for task " + id + ": require " + expected + " but was " + status);
        }
    }

    public void markQueued(Instant now) {
        if (status != TaskStatus.PENDING && status != TaskStatus.WAITING_APPROVAL) {
            throw new IllegalStateException(
                    "illegal transition for task " + id + ": QUEUED from " + status);
        }
        status = TaskStatus.QUEUED;
        touch(now);
    }

    public void markRunning(Instant now) {
        require(TaskStatus.QUEUED);
        status = TaskStatus.RUNNING;
        touch(now);
    }

    public void markWaitingApproval(Instant now, Instant deadline) {
        if (status != TaskStatus.RUNNING && status != TaskStatus.QUEUED) {
            throw new IllegalStateException(
                    "illegal transition for task " + id + ": WAITING_APPROVAL from " + status);
        }
        status = TaskStatus.WAITING_APPROVAL;
        this.deadline = deadline;
        touch(now);
    }

    public void approve(Instant now) {
        require(TaskStatus.WAITING_APPROVAL);
        approvalGranted = true;
        status = TaskStatus.QUEUED;
        touch(now);
    }

    public void reject(Instant now) {
        require(TaskStatus.WAITING_APPROVAL);
        status = TaskStatus.APPROVAL_REJECTED;
        touch(now);
    }

    public void timeoutApproval(Instant now) {
        require(TaskStatus.WAITING_APPROVAL);
        status = TaskStatus.APPROVAL_TIMEOUT;
        touch(now);
    }

    public void cancel(Instant now) {
        if (status.isTerminal()) {
            throw new IllegalStateException("cannot cancel terminal task " + id + " (" + status + ")");
        }
        status = TaskStatus.CANCELLED;
        touch(now);
    }

    public void markSucceeded(Instant now) {
        require(TaskStatus.RUNNING);
        status = TaskStatus.SUCCEEDED;
        touch(now);
    }

    public void markFailed(Instant now, String summary) {
        if (status != TaskStatus.RUNNING) {
            throw new IllegalStateException("cannot markFailed task " + id + " from " + status);
        }
        status = TaskStatus.FAILED;
        this.failureSummary = summary;
        touch(now);
    }

    public void mergeDuplicate(Instant now) {
        this.mergedDuplicateCount++;
        touch(now);
    }

    /**
     * 进程重启恢复：RUNNING 任务回到 QUEUED，并把任何非 SUCCEEDED 的步骤重置为 PENDING；
     * 已 SUCCEEDED 的步骤保持不变，因此恢复后绝不会重复执行。
     */
    public void markRecovered(Instant now) {
        for (TaskStep s : steps) {
            if (s.getStatus() != StepStatus.SUCCEEDED) {
                // 中断前未完成的步骤重置为全新待执行：状态与尝试次数都归零，
                // 恢复后获得完整的重试预算，且已成功的步骤不受影响。
                s.setStatus(StepStatus.PENDING);
                s.setAttempts(0);
                s.setLastError(null);
            }
        }
        if (status == TaskStatus.RUNNING) {
            status = TaskStatus.QUEUED;
        }
        touch(now);
    }

    @JsonIgnore
    public boolean isTerminal() {
        return status.isTerminal();
    }

    /**
     * 把本次更新的运行态字段合并到已持久化的最新快照上（存储层并发合并写使用）。
     * 这样执行线程持有的较旧任务对象在保存时，不会覆盖受理线程原子累加的重复计数等字段，
     * 因为这些字段直接取自磁盘上的最新版本。
     */
    public void mergeRuntimeFrom(RemediationTask incoming) {
        this.status = incoming.status;
        this.steps = incoming.steps;
        this.mergedDuplicateCount = Math.max(this.mergedDuplicateCount,
                incoming.mergedDuplicateCount);
        this.approvalGranted = this.approvalGranted || incoming.approvalGranted;
        this.deadline = incoming.deadline;
        this.failureSummary = incoming.failureSummary;
        this.updatedAt = incoming.updatedAt;
        this.version++;
    }

    public TaskStep findStep(String name) {
        for (TaskStep s : steps) {
            if (s.getName().equals(name)) {
                return s;
            }
        }
        return null;
    }

    // ---- getters / setters（Jackson 与存储层使用）----

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getFingerprintKey() { return fingerprintKey; }
    public void setFingerprintKey(String fingerprintKey) { this.fingerprintKey = fingerprintKey; }

    public String getAlertId() { return alertId; }
    public void setAlertId(String alertId) { this.alertId = alertId; }

    public String getTarget() { return target; }
    public void setTarget(String target) { this.target = target; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getStrategyId() { return strategyId; }
    public void setStrategyId(String strategyId) { this.strategyId = strategyId; }

    public RiskLevel getRiskLevel() { return riskLevel; }
    public void setRiskLevel(RiskLevel riskLevel) { this.riskLevel = riskLevel; }

    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }

    public long getRetryBackoffMs() { return retryBackoffMs; }
    public void setRetryBackoffMs(long retryBackoffMs) { this.retryBackoffMs = retryBackoffMs; }

    public TaskStatus getStatus() { return status; }
    public void setStatus(TaskStatus status) { this.status = status; }

    public List<TaskStep> getSteps() { return steps; }
    public void setSteps(List<TaskStep> steps) { this.steps = steps; }

    public int getMergedDuplicateCount() { return mergedDuplicateCount; }
    public void setMergedDuplicateCount(int mergedDuplicateCount) {
        this.mergedDuplicateCount = mergedDuplicateCount;
    }

    public boolean isApprovalGranted() { return approvalGranted; }
    public void setApprovalGranted(boolean approvalGranted) { this.approvalGranted = approvalGranted; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public Instant getDeadline() { return deadline; }
    public void setDeadline(Instant deadline) { this.deadline = deadline; }

    public String getFailureSummary() { return failureSummary; }
    public void setFailureSummary(String failureSummary) { this.failureSummary = failureSummary; }

    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }
}
