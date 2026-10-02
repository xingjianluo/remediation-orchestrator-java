package com.github.xingjianluo.remediationorchestratorjava.engine;

import com.github.xingjianluo.remediationorchestratorjava.approval.ApprovalService;
import com.github.xingjianluo.remediationorchestratorjava.config.OrchestratorProperties;
import com.github.xingjianluo.remediationorchestratorjava.maintenance.MaintenanceWindowService;
import com.github.xingjianluo.remediationorchestratorjava.model.*;
import com.github.xingjianluo.remediationorchestratorjava.store.AuditLog;
import com.github.xingjianluo.remediationorchestratorjava.store.TaskStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 告警受理入口。处理顺序：
 * <ol>
 *   <li>策略匹配：无启用策略命中 → REJECTED_NO_POLICY；</li>
 *   <li>维护窗口：命中窗口 → SUPPRESSED_MAINTENANCE（不建任务、无修复副作用）；</li>
 *   <li>去重：同指纹存在未终结任务 → DUPLICATE；</li>
 *   <li>否则创建任务：需审批则进 WAITING_APPROVAL，否则进 QUEUED 等待调度。</li>
 * </ol>
 * 每个分支都写审计并给出可区分的 reason。
 */
public class TaskEngine {

    private static final Logger log = LoggerFactory.getLogger(TaskEngine.class);

    private final TaskStore taskStore;
    private final AuditLog auditLog;
    private final PolicyMatcher matcher;
    private final MaintenanceWindowService maintenance;
    private final OrchestratorProperties props;
    private final Clock clock;
    private final RecurrenceService recurrenceService;
    private ApprovalService approvalService;

    public TaskEngine(TaskStore taskStore, AuditLog auditLog, PolicyMatcher matcher,
                      MaintenanceWindowService maintenance, OrchestratorProperties props, Clock clock,
                      RecurrenceService recurrenceService) {
        this.taskStore = taskStore;
        this.auditLog = auditLog;
        this.matcher = matcher;
        this.maintenance = maintenance;
        this.props = props;
        this.clock = clock;
        this.recurrenceService = recurrenceService;
    }

    public void setApprovalService(ApprovalService approvalService) {
        this.approvalService = approvalService;
    }

    public AcceptResult accept(Alert alert) {
        long now = clock.millis();
        log.info("[accept] input alertId={} target={} severity={} content='{}'",
                alert.id(), alert.target(), alert.severity(), alert.content());

        Optional<PolicyMatcher.MatchedPolicy> matched = matcher.match(alert);
        if (matched.isEmpty()) {
            String reason = "no enabled policy matches content";
            audit(alert, AuditEvents.ALERT_NO_POLICY, null, Map.of("reason", reason));
            log.info("[accept] REJECTED_NO_POLICY alertId={}", alert.id());
            return new AcceptResult(DispositionCode.REJECTED_NO_POLICY, null, reason);
        }
        RemediationPolicy policy = matched.get().policy();

        List<MaintenanceWindowRecord> windows = maintenance.activeWindows(alert.target(), now).stream()
                .map(w -> new MaintenanceWindowRecord(w.id(), w.reason())).toList();
        if (!windows.isEmpty()) {
            String reason = "target within maintenance windows " + windows.stream().map(MaintenanceWindowRecord::id).toList();
            audit(alert, AuditEvents.ALERT_MAINTENANCE_SUPPRESSED, null, Map.of(
                    "reason", reason,
                    "windows", windows));
            log.info("[accept] SUPPRESSED_MAINTENANCE alertId={} {}", alert.id(), reason);
            return new AcceptResult(DispositionCode.SUPPRESSED_MAINTENANCE, null, reason);
        }

        String fingerprint = fingerprint(policy, alert);
        Optional<RemediationTask> open = taskStore.findOpenByFingerprint(fingerprint);
        if (open.isPresent()) {
            RemediationTask existing = open.get();
            String reason = "open task " + existing.id + " with same fingerprint " + fingerprint
                    + " in state " + existing.state;
            audit(alert, AuditEvents.ALERT_DUPLICATE, existing.id, Map.of(
                    "fingerprint", fingerprint,
                    "existingState", existing.state.name(),
                    "reason", reason));
            log.info("[accept] DUPLICATE alertId={} -> task={} state={}",
                    alert.id(), existing.id, existing.state);
            return new AcceptResult(DispositionCode.DUPLICATE, existing.id, reason);
        }

        // 复发判定：仅当同指纹存在已终结任务时介入；未终结任务已在上面按 DUPLICATE 合并。
        RecurrenceService.Assessment recurrence = recurrenceService.assess(fingerprint, now);
        switch (recurrence.verdict()) {
            case SUPPRESS -> {
                audit(alert, AuditEvents.ALERT_RECURRENCE_SUPPRESSED, null, recurrenceDetail(fingerprint, recurrence));
                log.info("[accept] RECURRENCE_SUPPRESSED alertId={} {}", alert.id(), recurrence.reason());
                return new AcceptResult(DispositionCode.RECURRENCE_SUPPRESSED, null, recurrence.reason());
            }
            case ESCALATE -> {
                audit(alert, AuditEvents.ALERT_RECURRENCE_ESCALATED, null, recurrenceDetail(fingerprint, recurrence));
                log.info("[accept] RECURRENCE_ESCALATED alertId={} {}", alert.id(), recurrence.reason());
                return new AcceptResult(DispositionCode.RECURRENCE_ESCALATED, null,
                        recurrence.reason() + "; fingerprint=" + fingerprint);
            }
            case ABANDONED -> {
                audit(alert, AuditEvents.ALERT_RECURRENCE_ABANDONED, null, recurrenceDetail(fingerprint, recurrence));
                log.info("[accept] RECURRENCE_ABANDONED alertId={} {}", alert.id(), recurrence.reason());
                return new AcceptResult(DispositionCode.RECURRENCE_ABANDONED, null, recurrence.reason());
            }
            default -> {
                // NOT_RECURRENCE / REOPEN：继续创建任务
            }
        }
        boolean reopened = recurrence.verdict() == RecurrenceService.Verdict.REOPEN;

        RemediationTask task = newTask(alert, policy, fingerprint, now);
        if (policy.requiresApproval) {
            if (approvalService == null) {
                throw new IllegalStateException("ApprovalService not wired but policy " + policy.id + " requires approval");
            }
            approvalService.request(task, policy.approvalTimeoutSeconds);
            audit(alert, AuditEvents.ALERT_ACCEPTED, task.id, Map.of(
                    "policyId", policy.id,
                    "matchBasis", matched.get().reason(),
                    "fingerprint", fingerprint,
                    "state", TaskState.WAITING_APPROVAL.name()));
            log.info("[accept] CREATED (awaiting approval) alertId={} task={} policy={}",
                    alert.id(), task.id, policy.id);
        } else {
            taskStore.save(task);
            audit(alert, AuditEvents.TASK_QUEUED, task.id, Map.of(
                    "policyId", policy.id,
                    "matchBasis", matched.get().reason(),
                    "fingerprint", fingerprint,
                    "state", TaskState.QUEUED.name()));
            audit(alert, AuditEvents.ALERT_ACCEPTED, task.id, Map.of(
                    "policyId", policy.id,
                    "state", TaskState.QUEUED.name()));
            log.info("[accept] CREATED alertId={} task={} policy={} target={}",
                    alert.id(), task.id, policy.id, alert.target());
        }
        if (reopened) {
            audit(alert, AuditEvents.ALERT_RECURRENCE_REOPENED, task.id, recurrenceDetail(fingerprint, recurrence));
            log.info("[accept] RECURRENCE_REOPENED alertId={} task={} {}", alert.id(), task.id, recurrence.reason());
            return new AcceptResult(DispositionCode.RECURRENCE_REOPENED, task.id, recurrence.reason());
        }
        return new AcceptResult(DispositionCode.CREATED, task.id,
                "policy=" + policy.id + ", fingerprint=" + fingerprint);
    }

    private Map<String, Object> recurrenceDetail(String fingerprint, RecurrenceService.Assessment rec) {
        Map<String, Object> detail = new HashMap<>();
        detail.put("fingerprint", fingerprint);
        detail.put("recurrenceCount", rec.recurrenceCount());
        detail.put("threshold", rec.threshold());
        detail.put("lastTerminalState", rec.lastTerminalState() == null ? "" : rec.lastTerminalState().name());
        detail.put("lastFinishedAt", rec.lastFinishedAt());
        detail.put("suppressedUntil", rec.suppressedUntil());
        detail.put("decision", rec.decision().name());
        detail.put("reason", rec.reason());
        return detail;
    }

    /**
     * 主动取消：
     * <ul>
     *   <li>WAITING_APPROVAL：审批单置 CANCELLED，任务直接到 CANCELLED 终态；</li>
     *   <li>QUEUED / RETRY_WAIT：直接到 CANCELLED 终态，调度器不会再取；</li>
     *   <li>RUNNING：置 cancelRequested，当前步骤被中断，工作循环收尾为 CANCELLED。</li>
     * </ul>
     */
    public boolean cancel(String taskId, String reason) {
        RemediationTask task = taskStore.findById(taskId).orElse(null);
        if (task == null) {
            log.warn("[cancel] task {} not found", taskId);
            return false;
        }
        if (task.state.isTerminal()) {
            log.warn("[cancel] task {} already terminal {}", taskId, task.state);
            return false;
        }
        long now = clock.millis();
        switch (task.state) {
            case WAITING_APPROVAL -> {
                if (approvalService != null) {
                    approvalService.cancel(taskId, reason);
                }
                task = taskStore.findById(taskId).orElseThrow();
                task.state = TaskState.CANCELLED;
                task.terminalReason = "cancelled while awaiting approval: " + safe(reason);
                task.finishedAt = now;
                task.updatedAt = now;
                taskStore.save(task);
            }
            case QUEUED, RETRY_WAIT -> {
                task.state = TaskState.CANCELLED;
                task.cancelRequested = true;
                task.terminalReason = "cancelled: " + safe(reason);
                task.finishedAt = now;
                task.updatedAt = now;
                taskStore.save(task);
            }
            case RUNNING -> {
                task.cancelRequested = true;
                task.updatedAt = now;
                taskStore.save(task);
            }
            default -> {
                return false;
            }
        }
        auditLog.append(new AuditEvent(now, AuditEvents.TASK_CANCELLED, taskId,
                task.alert != null ? task.alert.id() : null,
                Map.of("reason", safe(reason), "stateAtCancel", task.state.name())));
        log.info("[cancel] requested task={} state={} reason={}", taskId, task.state, reason);
        return true;
    }

    private RemediationTask newTask(Alert alert, RemediationPolicy policy, String fingerprint, long now) {
        RemediationTask task = new RemediationTask();
        task.id = "task-" + UUID.randomUUID();
        task.fingerprint = fingerprint;
        task.policyId = policy.id;
        task.target = alert.target();
        task.alert = alert;
        task.state = TaskState.QUEUED;
        task.approval = ApprovalStatus.NONE;
        task.createdAt = now;
        task.updatedAt = now;
        task.queuedAt = now;
        task.steps = new ArrayList<>();
        for (int i = 0; i < policy.steps.size(); i++) {
            StepSpec spec = policy.steps.get(i);
            TaskStep step = new TaskStep();
            step.index = i;
            step.type = spec.type;
            step.name = spec.name;
            step.params = spec.params == null ? Map.of() : Map.copyOf(spec.params);
            step.state = StepState.PENDING;
            task.steps.add(step);
        }
        task.maxRetries = policy.maxRetries;
        task.backoffBaseMillis = policy.backoffBaseMillis;
        task.stepTimeoutMillis = policy.stepTimeoutMillis;
        return task;
    }

    /**
     * 等价告警指纹：策略 + 目标 + 告警正文规范化（小写、合并空白）。
     * 告警 ID 不参与指纹，因此重复推送的同一告警只产生一次修复。
     */
    public static String fingerprint(RemediationPolicy policy, Alert alert) {
        String normalized = alert.content() == null ? ""
                : alert.content().trim().toLowerCase().replaceAll("\\s+", " ");
        String raw = policy.id + "|" + alert.target() + "|" + normalized;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(raw.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        } catch (Exception e) {
            return Integer.toHexString(raw.hashCode());
        }
    }

    private void audit(Alert alert, String event, String taskId, Map<String, Object> detail) {
        Map<String, Object> all = new HashMap<>(detail);
        all.putIfAbsent("alertId", alert.id());
        all.putIfAbsent("target", alert.target());
        auditLog.append(new AuditEvent(clock.millis(), event, taskId, alert.id(), all));
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private record MaintenanceWindowRecord(String id, String reason) {
    }
}
