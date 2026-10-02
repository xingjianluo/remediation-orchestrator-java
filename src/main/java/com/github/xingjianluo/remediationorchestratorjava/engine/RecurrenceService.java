package com.github.xingjianluo.remediationorchestratorjava.engine;

import com.github.xingjianluo.remediationorchestratorjava.config.OrchestratorProperties;
import com.github.xingjianluo.remediationorchestratorjava.model.AuditEvent;
import com.github.xingjianluo.remediationorchestratorjava.model.AuditEvents;
import com.github.xingjianluo.remediationorchestratorjava.model.EscalationDecision;
import com.github.xingjianluo.remediationorchestratorjava.model.RemediationTask;
import com.github.xingjianluo.remediationorchestratorjava.model.TaskState;
import com.github.xingjianluo.remediationorchestratorjava.store.AuditLog;
import com.github.xingjianluo.remediationorchestratorjava.store.EscalationStore;
import com.github.xingjianluo.remediationorchestratorjava.store.TaskStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 复发判定与升级管理。
 *
 * <p>复发定义：同一去重指纹（策略 + 目标 + 规范化内容）已存在<b>终结</b>任务，
 * 等价告警再次到达即为复发。仍在 QUEUED / RETRY_WAIT / RUNNING / WAITING_APPROVAL
 * 的任务不算终结，由去重逻辑先行拦截（DUPLICATE），不参与复发计数。</p>
 *
 * <p>判定顺序：</p>
 * <ol>
 *   <li>统计窗 {@code recurrenceStatsWindowMillis} 内终结轮次 &ge; {@code recurrenceEscalationThreshold}
 *       → 升级：无人工决定或决定已被更新的复发超越 → ESCALATE；已放弃 → ABANDONED；
 *       已确认且计数未再增长 → REOPEN（确认放行，跳过抑制窗口）；</li>
 *   <li>否则距最近一次终结未超过 {@code recurrenceSuppressionWindowMillis} → SUPPRESS；</li>
 *   <li>否则 → REOPEN（放行一次新修复，后续重复告警仍由去重合并）。</li>
 * </ol>
 *
 * <p>所有计数与时间点均来自持久化的任务存储与决定存储，重启后连续；
 * 统计窗随时间滑动，旧终结记录滑出窗口后升级自然解除。</p>
 */
public class RecurrenceService {

    private static final Logger log = LoggerFactory.getLogger(RecurrenceService.class);

    public enum Verdict {
        /** 无终结历史，不是复发。 */
        NOT_RECURRENCE,
        /** 复发，允许重开一次修复。 */
        REOPEN,
        /** 复发，命中抑制窗口，不新增修复动作。 */
        SUPPRESS,
        /** 复发次数达阈值，需人工确认。 */
        ESCALATE,
        /** 已人工放弃，统计窗滑出前不再自动修复。 */
        ABANDONED
    }

    /** 一次复发判定的完整依据。 */
    public record Assessment(Verdict verdict, int recurrenceCount, int threshold,
                             TaskState lastTerminalState, long lastFinishedAt,
                             long suppressedUntil, EscalationDecision.Kind decision,
                             String reason) {
    }

    /** 对外查询视图：升级状态、当前复发计数与所依据的时间窗。 */
    public record RecurrenceStatus(String fingerprint, String target, String policyId,
                                   int recurrenceCount, long statsWindowMillis,
                                   int escalationThreshold, boolean escalated,
                                   String lastTerminalState, long lastFinishedAt,
                                   long suppressionWindowMillis, long suppressedUntil,
                                   boolean suppressed,
                                   EscalationDecision.Kind decision, int countAtDecision,
                                   String decidedBy, long decidedAt) {
    }

    private final TaskStore taskStore;
    private final EscalationStore escalationStore;
    private final AuditLog auditLog;
    private final OrchestratorProperties props;

    public RecurrenceService(TaskStore taskStore, EscalationStore escalationStore,
                             AuditLog auditLog, OrchestratorProperties props) {
        this.taskStore = taskStore;
        this.escalationStore = escalationStore;
        this.auditLog = auditLog;
        this.props = props;
    }

    /** 对指定指纹在 {@code now} 时刻做复发判定。 */
    public Assessment assess(String fingerprint, long now) {
        List<RemediationTask> terminal = terminalTasks(fingerprint);
        if (terminal.isEmpty()) {
            return new Assessment(Verdict.NOT_RECURRENCE, 0, props.getRecurrenceEscalationThreshold(),
                    null, 0, 0, EscalationDecision.Kind.NONE, "no terminal history for fingerprint");
        }
        RemediationTask last = terminal.stream()
                .max(Comparator.comparingLong(t -> t.finishedAt)).orElseThrow();
        long windowStart = now - props.getRecurrenceStatsWindowMillis();
        int count = (int) terminal.stream().filter(t -> t.finishedAt >= windowStart).count();
        int threshold = props.getRecurrenceEscalationThreshold();
        long suppressedUntil = last.finishedAt + props.getRecurrenceSuppressionWindowMillis();
        EscalationDecision decision = escalationStore.find(fingerprint)
                .orElse(new EscalationDecision());

        if (threshold > 0 && count >= threshold) {
            if (decision.kind == EscalationDecision.Kind.CONFIRMED
                    && count <= decision.countAtDecision) {
                String reason = "escalation confirmed by " + safe(decision.decidedBy)
                        + " at count " + decision.countAtDecision
                        + "; reopening after manual confirmation (previous "
                        + last.state + " at " + last.finishedAt + ")";
                log.info("[recurrence] fingerprint={} REOPEN (confirmed): count={}/{}",
                        fingerprint, count, threshold);
                return new Assessment(Verdict.REOPEN, count, threshold, last.state,
                        last.finishedAt, suppressedUntil, decision.kind, reason);
            }
            if (decision.kind == EscalationDecision.Kind.ABANDONED
                    && count <= decision.countAtDecision) {
                String reason = "recurrence abandoned by " + safe(decision.decidedBy)
                        + " at count " + decision.countAtDecision
                        + "; no auto remediation until stats window slides below threshold";
                log.info("[recurrence] fingerprint={} ABANDONED: count={}/{}",
                        fingerprint, count, threshold);
                return new Assessment(Verdict.ABANDONED, count, threshold, last.state,
                        last.finishedAt, suppressedUntil, decision.kind, reason);
            }
            String reason = "recurrence count " + count + " >= threshold " + threshold
                    + " within stats window " + props.getRecurrenceStatsWindowMillis()
                    + "ms; manual confirmation required (previous " + last.state
                    + " at " + last.finishedAt + ")";
            log.info("[recurrence] fingerprint={} ESCALATE: count={}/{} window={}ms",
                    fingerprint, count, threshold, props.getRecurrenceStatsWindowMillis());
            return new Assessment(Verdict.ESCALATE, count, threshold, last.state,
                    last.finishedAt, suppressedUntil, decision.kind, reason);
        }

        if (now < suppressedUntil) {
            String reason = "within suppression window: previous remediation "
                    + last.state + " at " + last.finishedAt + ", suppressed until "
                    + suppressedUntil + " (window " + props.getRecurrenceSuppressionWindowMillis() + "ms)";
            log.info("[recurrence] fingerprint={} SUPPRESS until={} (previous {} at {})",
                    fingerprint, suppressedUntil, last.state, last.finishedAt);
            return new Assessment(Verdict.SUPPRESS, count, threshold, last.state,
                    last.finishedAt, suppressedUntil, decision.kind, reason);
        }

        String reason = "recurrence after suppression window (previous " + last.state
                + " at " + last.finishedAt + ", count " + count + "/" + threshold
                + "); reopening one remediation";
        log.info("[recurrence] fingerprint={} REOPEN: previous {} at {}, count={}/{}",
                fingerprint, last.state, last.finishedAt, count, threshold);
        return new Assessment(Verdict.REOPEN, count, threshold, last.state,
                last.finishedAt, suppressedUntil, decision.kind, reason);
    }

    /** 人工确认：放行当前升级（决定覆盖当前计数，计数再增长会重新升级）。 */
    public EscalationDecision confirm(String fingerprint, String operator, String note, long now) {
        int count = currentCount(fingerprint, now);
        EscalationDecision decision = new EscalationDecision(fingerprint,
                EscalationDecision.Kind.CONFIRMED, count, now, operator, note);
        escalationStore.save(decision);
        auditLog.append(new AuditEvent(now, AuditEvents.ESCALATION_CONFIRMED, null, null, Map.of(
                "fingerprint", fingerprint,
                "countAtDecision", count,
                "decidedBy", safe(operator),
                "note", safe(note))));
        log.info("[recurrence] fingerprint={} CONFIRMED by {} at count {}", fingerprint, operator, count);
        return decision;
    }

    /** 人工放弃：统计窗滑出阈值前不再自动修复该指纹的告警。 */
    public EscalationDecision abandon(String fingerprint, String operator, String note, long now) {
        int count = currentCount(fingerprint, now);
        EscalationDecision decision = new EscalationDecision(fingerprint,
                EscalationDecision.Kind.ABANDONED, count, now, operator, note);
        escalationStore.save(decision);
        auditLog.append(new AuditEvent(now, AuditEvents.ESCALATION_ABANDONED, null, null, Map.of(
                "fingerprint", fingerprint,
                "countAtDecision", count,
                "decidedBy", safe(operator),
                "note", safe(note))));
        log.info("[recurrence] fingerprint={} ABANDONED by {} at count {}", fingerprint, operator, count);
        return decision;
    }

    /** 查询某一指纹的复发状态。 */
    public Optional<RecurrenceStatus> status(String fingerprint, long now) {
        List<RemediationTask> all = taskStore.findByFingerprint(fingerprint);
        if (all.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(toStatus(fingerprint, all, now));
    }

    /** 查询某一目标下全部有终结历史的指纹复发状态。 */
    public List<RecurrenceStatus> statusForTarget(String target, long now) {
        return toStatuses(taskStore.findByTarget(target), now);
    }

    /** 查询全部有终结历史的指纹复发状态。 */
    public List<RecurrenceStatus> statusAll(long now) {
        return toStatuses(taskStore.findAll(), now);
    }

    private List<RecurrenceStatus> toStatuses(List<RemediationTask> tasks, long now) {
        return tasks.stream()
                .collect(java.util.stream.Collectors.groupingBy(t -> t.fingerprint,
                        java.util.stream.Collectors.toList()))
                .entrySet().stream()
                .filter(e -> e.getValue().stream().anyMatch(this::isTerminal))
                .map(e -> toStatus(e.getKey(), e.getValue(), now))
                .toList();
    }

    private RecurrenceStatus toStatus(String fingerprint, List<RemediationTask> tasks, long now) {
        List<RemediationTask> terminal = tasks.stream().filter(this::isTerminal).toList();
        RemediationTask any = tasks.stream()
                .max(Comparator.comparingLong(t -> t.createdAt)).orElseThrow();
        RemediationTask last = terminal.stream()
                .max(Comparator.comparingLong(t -> t.finishedAt)).orElse(null);
        long windowStart = now - props.getRecurrenceStatsWindowMillis();
        int count = (int) terminal.stream().filter(t -> t.finishedAt >= windowStart).count();
        int threshold = props.getRecurrenceEscalationThreshold();
        long suppressedUntil = last == null ? 0
                : last.finishedAt + props.getRecurrenceSuppressionWindowMillis();
        EscalationDecision decision = escalationStore.find(fingerprint)
                .orElse(new EscalationDecision());
        boolean escalated = threshold > 0 && count >= threshold
                && !(decision.kind == EscalationDecision.Kind.CONFIRMED
                        && count <= decision.countAtDecision)
                && !(decision.kind == EscalationDecision.Kind.ABANDONED
                        && count <= decision.countAtDecision);
        return new RecurrenceStatus(fingerprint, any.target, any.policyId,
                count, props.getRecurrenceStatsWindowMillis(), threshold, escalated,
                last == null ? null : last.state.name(),
                last == null ? 0 : last.finishedAt,
                props.getRecurrenceSuppressionWindowMillis(), suppressedUntil,
                last != null && now < suppressedUntil,
                decision.kind, decision.countAtDecision, decision.decidedBy, decision.decidedAt);
    }

    private int currentCount(String fingerprint, long now) {
        long windowStart = now - props.getRecurrenceStatsWindowMillis();
        return (int) terminalTasks(fingerprint).stream()
                .filter(t -> t.finishedAt >= windowStart).count();
    }

    private List<RemediationTask> terminalTasks(String fingerprint) {
        return taskStore.findByFingerprint(fingerprint).stream()
                .filter(this::isTerminal)
                .toList();
    }

    /** 只把真正终结（终态且有终结时间）的任务计入复发历史；排队/重试中的不算。 */
    private boolean isTerminal(RemediationTask t) {
        return t.state.isTerminal() && t.finishedAt > 0;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
