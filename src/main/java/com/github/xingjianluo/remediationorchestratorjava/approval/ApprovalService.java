package com.github.xingjianluo.remediationorchestratorjava.approval;

import com.github.xingjianluo.remediationorchestratorjava.config.OrchestratorProperties;
import com.github.xingjianluo.remediationorchestratorjava.model.ApprovalStatus;
import com.github.xingjianluo.remediationorchestratorjava.model.AuditEvent;
import com.github.xingjianluo.remediationorchestratorjava.model.AuditEvents;
import com.github.xingjianluo.remediationorchestratorjava.model.RemediationTask;
import com.github.xingjianluo.remediationorchestratorjava.model.TaskState;
import com.github.xingjianluo.remediationorchestratorjava.store.AuditLog;
import com.github.xingjianluo.remediationorchestratorjava.store.TaskStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;

/**
 * 高风险修复的人工审批。
 * 批准 / 拒绝 / 超时 / 取消分别落为 APPROVED / REJECTED / TIMED_OUT / CANCELLED，
 * 拒绝、超时把任务推进到 APPROVAL_REJECTED / APPROVAL_TIMEOUT 终态；取消由 TaskEngine 负责终态。
 */
public class ApprovalService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalService.class);

    private final TaskStore taskStore;
    private final AuditLog auditLog;
    private final OrchestratorProperties props;
    private final Clock clock;

    public ApprovalService(TaskStore taskStore, AuditLog auditLog, OrchestratorProperties props) {
        this(taskStore, auditLog, props, Clock.systemUTC());
    }

    public ApprovalService(TaskStore taskStore, AuditLog auditLog, OrchestratorProperties props, Clock clock) {
        this.taskStore = taskStore;
        this.auditLog = auditLog;
        this.props = props;
        this.clock = clock;
    }

    /**
     * 任务进入待审批状态并登记截止时间。
     *
     * @param policyTimeoutSeconds 策略声明的审批超时秒数，&lt;=0 时使用全局默认
     */
    public void request(RemediationTask task, int policyTimeoutSeconds) {
        int timeoutSeconds = policyTimeoutSeconds > 0
                ? policyTimeoutSeconds
                : props.getDefaultApprovalTimeoutSeconds();
        long deadline = clock.millis() + timeoutSeconds * 1000L;
        task.approval = ApprovalStatus.PENDING;
        task.state = TaskState.WAITING_APPROVAL;
        task.approvalDeadline = deadline;
        task.updatedAt = clock.millis();
        taskStore.save(task);
        Map<String, Object> detail = new HashMap<>();
        detail.put("requiresApproval", true);
        detail.put("timeoutSeconds", timeoutSeconds);
        detail.put("deadline", deadline);
        auditLog.append(new AuditEvent(clock.millis(), AuditEvents.APPROVAL_REQUESTED,
                task.id, task.alert != null ? task.alert.id() : null, detail));
        log.info("[approval] requested task={} timeoutSeconds={}", task.id, timeoutSeconds);
    }

    public synchronized boolean grant(String taskId, String approver) {
        RemediationTask task = taskStore.findById(taskId).orElse(null);
        if (task == null) {
            log.warn("[approval] grant ignored: task {} not found", taskId);
            return false;
        }
        if (task.approval != ApprovalStatus.PENDING) {
            log.warn("[approval] grant ignored: task={} approval={}", taskId, task.approval);
            return false;
        }
        task.approval = ApprovalStatus.APPROVED;
        task.state = TaskState.QUEUED;
        task.updatedAt = clock.millis();
        taskStore.save(task);
        auditLog.append(new AuditEvent(clock.millis(), AuditEvents.APPROVAL_GRANTED, taskId,
                alertId(task), Map.of("approver", nullSafe(approver))));
        log.info("[approval] granted task={} by={}", taskId, approver);
        return true;
    }

    public synchronized boolean reject(String taskId, String approver, String reason) {
        RemediationTask task = taskStore.findById(taskId).orElse(null);
        if (task == null || task.approval != ApprovalStatus.PENDING) {
            log.warn("[approval] reject ignored: task={} exists={}", taskId, task != null);
            return false;
        }
        task.approval = ApprovalStatus.REJECTED;
        task.state = TaskState.APPROVAL_REJECTED;
        task.terminalReason = "approval rejected: " + nullSafe(reason);
        task.finishedAt = clock.millis();
        task.updatedAt = clock.millis();
        taskStore.save(task);
        Map<String, Object> detail = new HashMap<>();
        detail.put("approver", nullSafe(approver));
        detail.put("reason", nullSafe(reason));
        auditLog.append(new AuditEvent(clock.millis(), AuditEvents.APPROVAL_REJECTED, taskId,
                alertId(task), detail));
        log.info("[approval] rejected task={} by={} reason={}", taskId, approver, reason);
        return true;
    }

    public synchronized boolean cancel(String taskId, String reason) {
        RemediationTask task = taskStore.findById(taskId).orElse(null);
        if (task == null) {
            return false;
        }
        // 仅待审批的审批单需要显式转为 CANCELLED；运行中任务的审批此前必已 APPROVED。
        if (task.approval != ApprovalStatus.PENDING) {
            return false;
        }
        task.approval = ApprovalStatus.CANCELLED;
        task.updatedAt = clock.millis();
        taskStore.save(task);
        // 终态迁移与审计由调用方（TaskEngine.cancel）统一记录，避免重复事件。
        log.info("[approval] marked CANCELLED task={} reason={}", taskId, reason);
        return true;
    }

    /** 扫描并置超时的审批。返回受影响任务数。 */
    public synchronized int sweepTimeouts(long nowMillis) {
        int n = 0;
        for (RemediationTask task : taskStore.findByState(TaskState.WAITING_APPROVAL)) {
            if (task.approval == ApprovalStatus.PENDING && task.approvalDeadline > 0
                    && nowMillis >= task.approvalDeadline) {
                task.approval = ApprovalStatus.TIMED_OUT;
                task.state = TaskState.APPROVAL_TIMEOUT;
                task.terminalReason = "approval timed out at " + task.approvalDeadline;
                task.finishedAt = nowMillis;
                task.updatedAt = nowMillis;
                taskStore.save(task);
                auditLog.append(new AuditEvent(nowMillis, AuditEvents.APPROVAL_TIMEOUT, task.id,
                        alertId(task), Map.of("deadline", task.approvalDeadline)));
                log.info("[approval] timeout task={} deadline={}", task.id, task.approvalDeadline);
                n++;
            }
        }
        return n;
    }

    private static String alertId(RemediationTask task) {
        return task.alert != null ? task.alert.id() : null;
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }
}
