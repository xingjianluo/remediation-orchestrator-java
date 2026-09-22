package com.github.xingjianluo.remediationorchestratorjava.engine;

import com.github.xingjianluo.remediationorchestratorjava.model.ApprovalStatus;
import com.github.xingjianluo.remediationorchestratorjava.model.AuditEvent;
import com.github.xingjianluo.remediationorchestratorjava.model.AuditEvents;
import com.github.xingjianluo.remediationorchestratorjava.model.RemediationTask;
import com.github.xingjianluo.remediationorchestratorjava.model.StepState;
import com.github.xingjianluo.remediationorchestratorjava.model.TaskState;
import com.github.xingjianluo.remediationorchestratorjava.store.AuditLog;
import com.github.xingjianluo.remediationorchestratorjava.store.TaskStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 进程重启后的状态恢复。
 * <ul>
 *   <li>RUNNING：崩溃时的当前步骤只可能处于未完成状态，重置为 PENDING 后重新排队，整步重跑（天然幂等由
 *       “已 SUCCEEDED 的步骤不再执行”保证，MARKER 等副作用只发生在成功路径）；</li>
 *   <li>RETRY_WAIT：直接重新排队，重试计数 attempts 已持久化，退避重新计算；</li>
 *   <li>QUEUED：保持排队；</li>
 *   <li>WAITING_APPROVAL：审批结论是外部决策，继续等待；若截止时间已过则置 APPROVAL_TIMEOUT；</li>
 *   <li>终态任务一律不触碰。</li>
 * </ul>
 */
public class RecoveryService {

    private static final Logger log = LoggerFactory.getLogger(RecoveryService.class);

    private final TaskStore taskStore;
    private final AuditLog auditLog;
    private final Clock clock;

    public RecoveryService(TaskStore taskStore, AuditLog auditLog, Clock clock) {
        this.taskStore = taskStore;
        this.auditLog = auditLog;
        this.clock = clock;
    }

    /** 返回恢复（重新排队）的任务数。 */
    public int recoverOnStartup() {
        long now = clock.millis();
        List<RemediationTask> open = taskStore.findNonTerminal();
        int resumed = 0;
        int resumedSteps = 0;
        int timedOutApprovals = 0;
        for (RemediationTask task : open) {
            TaskState before = task.state;
            switch (before) {
                case RUNNING, RETRY_WAIT -> {
                    for (var step : task.steps) {
                        if (step.state == StepState.RUNNING) {
                            step.state = StepState.PENDING;
                            step.updatedAt = now;
                            resumedSteps++;
                        }
                    }
                    task.state = TaskState.QUEUED;
                    task.nextAttemptAt = 0;
                    task.updatedAt = now;
                    taskStore.save(task);
                    auditLog.append(new AuditEvent(now, AuditEvents.RECOVERY_RESUMED, task.id,
                            alertId(task), Map.of(
                                    "from", before.name(),
                                    "to", TaskState.QUEUED.name(),
                                    "resetRunningSteps", resumedSteps)));
                    log.info("[recovery] task={} {} -> QUEUED, completed steps preserved: {}",
                            task.id, before, completedStepNames(task));
                    resumed++;
                }
                case QUEUED -> {
                    task.nextAttemptAt = 0;
                    task.updatedAt = now;
                    taskStore.save(task);
                    auditLog.append(new AuditEvent(now, AuditEvents.RECOVERY_RESUMED, task.id,
                            alertId(task), Map.of("from", before.name(), "to", TaskState.QUEUED.name())));
                    resumed++;
                }
                case WAITING_APPROVAL -> {
                    if (task.approval == ApprovalStatus.PENDING && task.approvalDeadline > 0
                            && now >= task.approvalDeadline) {
                        task.approval = ApprovalStatus.TIMED_OUT;
                        task.state = TaskState.APPROVAL_TIMEOUT;
                        task.terminalReason = "approval timed out during downtime at " + task.approvalDeadline;
                        task.finishedAt = now;
                        task.updatedAt = now;
                        taskStore.save(task);
                        auditLog.append(new AuditEvent(now, AuditEvents.APPROVAL_TIMEOUT, task.id,
                                alertId(task), Map.of("deadline", task.approvalDeadline, "duringRestart", true)));
                        timedOutApprovals++;
                        log.info("[recovery] task={} approval timed out during downtime", task.id);
                    } else {
                        log.info("[recovery] task={} remains WAITING_APPROVAL (decision external)", task.id);
                    }
                }
                default -> log.info("[recovery] task={} left in state {}", task.id, before);
            }
        }
        Map<String, Object> summary = new HashMap<>();
        summary.put("openTasks", open.size());
        summary.put("resumed", resumed);
        summary.put("resetRunningSteps", resumedSteps);
        summary.put("timedOutApprovals", timedOutApprovals);
        auditLog.append(new AuditEvent(now, AuditEvents.RECOVERY_SCAN, null, null, summary));
        log.info("[recovery] scan complete: {}", summary);
        return resumed;
    }

    private static List<String> completedStepNames(RemediationTask task) {
        return task.steps.stream()
                .filter(s -> s.state == StepState.SUCCEEDED)
                .map(s -> s.index + ":" + s.name)
                .toList();
    }

    private static String alertId(RemediationTask task) {
        return task.alert != null ? task.alert.id() : null;
    }
}
