package com.github.xingjianluo.remediationorchestratorjava.engine;

import com.github.xingjianluo.remediationorchestratorjava.approval.ApprovalService;
import com.github.xingjianluo.remediationorchestratorjava.config.OrchestratorProperties;
import com.github.xingjianluo.remediationorchestratorjava.maintenance.MaintenanceWindowService;
import com.github.xingjianluo.remediationorchestratorjava.model.*;
import com.github.xingjianluo.remediationorchestratorjava.recurrence.RecurrenceService;
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

        RemediationTask previous = latestTerminalByFingerprint(fingerprint);
        if (previous != null) {
            return handleRecurrence(alert, policy, matched.get(), fingerprint, previous, now);
        }

        return createTask(alert, policy, matched.get(), fingerprint, now, Map.of());
    }

    /**
     * 复发路径：同指纹任务已终结后等价告警再次到达。处理顺序：
     * <ol>
     *   <li>记录复发事件（持久化，驱动升级判定）；</li>
     *   <li>人工放弃期内 → RECURRENCE_ABANDONED；</li>
     *   <li>有待消费的人工确认 → 消费并放行一次真实修复（CREATED）；</li>
     *   <li>统计窗内复发次数达阈值 → RECURRENCE_ESCALATED（需人工确认，无任何副作用）；</li>
     *   <li>前次修复成功：抑制窗口内 → RECURRENCE_SUPPRESSED；窗口过后 → 放行一次新修复（CREATED）；</li>
     *   <li>前次修复未成功（FAILED/CANCELLED/审批拒绝/审批超时）→ RECURRENCE_AFTER_FAILURE（需人工确认）。</li>
     * </ol>
     * 放行的修复走正常 QUEUED 流程，仍受同目标串行与全局并发上限约束。
     */
    private AcceptResult handleRecurrence(Alert alert, RemediationPolicy policy,
                                          PolicyMatcher.MatchedPolicy matched, String fingerprint,
                                          RemediationTask previous, long now) {
        int count = recurrenceService.recordRecurrence(alert.target());
        Map<String, Object> base = new HashMap<>(Map.of(
                "fingerprint", fingerprint,
                "previousTaskId", previous.id,
                "previousState", previous.state.name(),
                "recurrenceCount", count,
                "escalationThreshold", props.getRecurrenceEscalationThreshold(),
                "statsWindowMillis", props.getRecurrenceStatsWindowMillis(),
                "suppressionWindowMillis", props.getRecurrenceSuppressionWindowMillis()));

        if (recurrenceService.isAbandoned(alert.target())) {
            String reason = "target " + alert.target() + " abandoned by operator; no auto-remediation within stats window";
            audit(alert, AuditEvents.ALERT_RECURRENCE_ABANDONED, previous.id, withReason(base, reason));
            log.info("[accept] RECURRENCE_ABANDONED alertId={} target={}", alert.id(), alert.target());
            return new AcceptResult(DispositionCode.RECURRENCE_ABANDONED, previous.id, reason);
        }

        if (recurrenceService.hasConfirmation(alert.target())) {
            recurrenceService.consumeConfirmation(alert.target());
            String reason = "recurrence confirmed by operator; reopening remediation after task "
                    + previous.id + " (" + previous.state + ")";
            AcceptResult result = createTask(alert, policy, matched, fingerprint, now,
                    Map.of("reopen", "operator-confirmed", "previousTaskId", previous.id));
            log.info("[accept] RECURRENCE confirmed-reopen alertId={} task={}", alert.id(), result.taskId());
            return result;
        }

        if (count >= props.getRecurrenceEscalationThreshold()) {
            String reason = "recurrence count " + count + " reached threshold "
                    + props.getRecurrenceEscalationThreshold() + " within stats window "
                    + props.getRecurrenceStatsWindowMillis() + "ms; manual confirmation required";
            audit(alert, AuditEvents.ALERT_RECURRENCE_ESCALATED, previous.id, withReason(base, reason));
            log.info("[accept] RECURRENCE_ESCALATED alertId={} target={} count={}",
                    alert.id(), alert.target(), count);
            return new AcceptResult(DispositionCode.RECURRENCE_ESCALATED, previous.id, reason);
        }

        if (previous.state == TaskState.SUCCEEDED) {
            long elapsed = now - previous.finishedAt;
            if (elapsed < props.getRecurrenceSuppressionWindowMillis()) {
                String reason = "recurrence within suppression window (" + elapsed + "ms elapsed of "
                        + props.getRecurrenceSuppressionWindowMillis() + "ms) after succeeded task " + previous.id;
                audit(alert, AuditEvents.ALERT_RECURRENCE_SUPPRESSED, previous.id, withReason(base, reason));
                log.info("[accept] RECURRENCE_SUPPRESSED alertId={} elapsed={}ms", alert.id(), elapsed);
                return new AcceptResult(DispositionCode.RECURRENCE_SUPPRESSED, previous.id, reason);
            }
            String reason = "suppression window elapsed after succeeded task " + previous.id
                    + "; reopening remediation (recurrence #" + count + ")";
            AcceptResult result = createTask(alert, policy, matched, fingerprint, now,
                    Map.of("reopen", "suppression-window-elapsed", "previousTaskId", previous.id));
            log.info("[accept] RECURRENCE reopen alertId={} task={} prev={}",
                    alert.id(), result.taskId(), previous.id);
            return result;
        }

        String reason = "previous task " + previous.id + " ended " + previous.state
                + "; equivalent alert requires manual confirmation before retry";
        audit(alert, AuditEvents.ALERT_RECURRENCE_AFTER_FAILURE, previous.id, withReason(base, reason));
        log.info("[accept] RECURRENCE_AFTER_FAILURE alertId={} prev={} state={}",
                alert.id(), previous.id, previous.state);
        return new AcceptResult(DispositionCode.RECURRENCE_AFTER_FAILURE, previous.id, reason);
    }

    /** 同指纹最近的终态任务（复发判定的基准），无则返回 null。 */
    private RemediationTask latestTerminalByFingerprint(String fingerprint) {
        return taskStore.findByFingerprint(fingerprint).stream()
                .filter(t -> t.state.isTerminal())
                .max(java.util.Comparator.comparingLong(t -> t.finishedAt))
                .orElse(null);
    }

    private AcceptResult createTask(Alert alert, RemediationPolicy policy, PolicyMatcher.MatchedPolicy matched,
                                    String fingerprint, long now, Map<String, Object> extraAudit) {
        RemediationTask task = newTask(alert, policy, fingerprint, now);
        Map<String, Object> detail = new HashMap<>(Map.of(
                "policyId", policy.id,
                "matchBasis", matched.reason(),
                "fingerprint", fingerprint));
        detail.putAll(extraAudit);
        if (policy.requiresApproval) {
            if (approvalService == null) {
                throw new IllegalStateException("ApprovalService not wired but policy " + policy.id + " requires approval");
            }
            approvalService.request(task, policy.approvalTimeoutSeconds);
            detail.put("state", TaskState.WAITING_APPROVAL.name());
            audit(alert, AuditEvents.ALERT_ACCEPTED, task.id, detail);
            log.info("[accept] CREATED (awaiting approval) alertId={} task={} policy={}",
                    alert.id(), task.id, policy.id);
        } else {
            taskStore.save(task);
            detail.put("state", TaskState.QUEUED.name());
            audit(alert, AuditEvents.TASK_QUEUED, task.id, detail);
            audit(alert, AuditEvents.ALERT_ACCEPTED, task.id, Map.of(
                    "policyId", policy.id,
                    "state", TaskState.QUEUED.name()));
            if (!extraAudit.isEmpty()) {
                audit(alert, AuditEvents.ALERT_RECURRENCE_REOPENED, task.id, detail);
            }
            log.info("[accept] CREATED alertId={} task={} policy={} target={}",
                    alert.id(), task.id, policy.id, alert.target());
        }
        return new AcceptResult(DispositionCode.CREATED, task.id,
                "policy=" + policy.id + ", fingerprint=" + fingerprint
                        + (extraAudit.isEmpty() ? "" : ", " + extraAudit.get("reopen")));
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

    private static Map<String, Object> withReason(Map<String, Object> base, String reason) {
        Map<String, Object> all = new HashMap<>(base);
        all.put("reason", reason);
        return all;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private record MaintenanceWindowRecord(String id, String reason) {
    }
}
