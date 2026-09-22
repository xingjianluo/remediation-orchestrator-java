package com.github.xingjianluo.remediationorchestratorjava.engine;

import com.github.xingjianluo.remediationorchestratorjava.model.Alert;
import com.github.xingjianluo.remediationorchestratorjava.model.AlertDisposition;
import com.github.xingjianluo.remediationorchestratorjava.model.DispositionReason;
import com.github.xingjianluo.remediationorchestratorjava.model.MaintenanceWindow;
import com.github.xingjianluo.remediationorchestratorjava.model.RemediationStrategy;
import com.github.xingjianluo.remediationorchestratorjava.model.RemediationTask;
import com.github.xingjianluo.remediationorchestratorjava.model.AuditType;
import com.github.xingjianluo.remediationorchestratorjava.store.TaskStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 告警受理门面，处理顺序固定：
 * <ol>
 *   <li>去重：等价告警命中进行中任务则合并（DUPLICATE_MERGED）；
 *       命中已终态任务则直接返回（DUPLICATE_TERMINAL），不会再次修复；</li>
 *   <li>维护窗口：窗口内抑制（SUPPRESSED_MAINTENANCE），无任何修复副作用；</li>
 *   <li>策略匹配：无匹配拒绝（REJECTED_NO_STRATEGY）；</li>
 *   <li>生成 PENDING 任务并入队（ACCEPTED）。</li>
 * </ol>
 * 每个分支都写可区分原因的审计事件。
 */
public class AlertIntakeService {

    private static final Logger log = LoggerFactory.getLogger(AlertIntakeService.class);

    private final DeduplicationService dedup;
    private final MaintenanceWindowService windows;
    private final StrategyMatcher matcher;
    private final TaskStore tasks;
    private final AuditService audit;
    private final TaskDispatcher dispatcher;
    private final Clock clock;

    public AlertIntakeService(DeduplicationService dedup, MaintenanceWindowService windows,
                              StrategyMatcher matcher, TaskStore tasks, AuditService audit,
                              TaskDispatcher dispatcher, Clock clock) {
        this.dedup = dedup;
        this.windows = windows;
        this.matcher = matcher;
        this.tasks = tasks;
        this.audit = audit;
        this.dispatcher = dispatcher;
        this.clock = clock;
    }

    public AlertDisposition accept(Alert alert) {
        Instant now = Instant.now(clock);
        String key = dedup.fingerprint(alert);
        log.info("[INTAKE] INPUT alert={} target={} type={} content='{}' fingerprint={}",
                alert.alertId(), alert.target(), alert.type(), alert.content(), key);

        Optional<RemediationTask> existing = tasks.findLatestByFingerprint(key);
        if (existing.isPresent()) {
            RemediationTask t0 = existing.get();
            if (t0.isTerminal()) {
                audit.record(AuditType.ALERT_DUPLICATE, t0.getId(), alert.alertId(),
                        DispositionReason.DUPLICATE_TERMINAL.name(),
                        Map.of("terminalStatus", t0.getStatus().name()));
                log.info("[INTAKE] DECISION alert={} -> DUPLICATE_TERMINAL task={} status={}",
                        alert.alertId(), t0.getId(), t0.getStatus());
                return AlertDisposition.duplicate(t0.getId(),
                        DispositionReason.DUPLICATE_TERMINAL);
            }
            // 通过存储层原子累加计数，避免与执行线程持有的任务快照互相覆盖
            int merged = tasks.incrementMergedDuplicate(t0.getId());
            audit.record(AuditType.ALERT_DUPLICATE, t0.getId(), alert.alertId(),
                    DispositionReason.DUPLICATE_MERGED.name(),
                    Map.of("mergedCount", String.valueOf(merged)));
            log.info("[INTAKE] DECISION alert={} -> DUPLICATE_MERGED into task={} (merged={})",
                    alert.alertId(), t0.getId(), merged);
            return AlertDisposition.duplicate(t0.getId(), DispositionReason.DUPLICATE_MERGED);
        }

        MaintenanceWindow window = windows.findCoveringNow(alert.target());
        if (window != null) {
            audit.record(AuditType.ALERT_SUPPRESSED, null, alert.alertId(),
                    DispositionReason.SUPPRESSED_MAINTENANCE.name(),
                    Map.of("windowId", window.getId(), "targetPrefix", window.getTargetPrefix()));
            log.info("[INTAKE] DECISION alert={} -> SUPPRESSED_MAINTENANCE window={}",
                    alert.alertId(), window.getId());
            return AlertDisposition.suppressed(window.getId());
        }

        Optional<RemediationStrategy> strategy = matcher.match(alert);
        if (strategy.isEmpty()) {
            audit.record(AuditType.ALERT_REJECTED, null, alert.alertId(),
                    DispositionReason.REJECTED_NO_STRATEGY.name());
            log.info("[INTAKE] DECISION alert={} -> REJECTED_NO_STRATEGY", alert.alertId());
            return AlertDisposition.rejected(DispositionReason.REJECTED_NO_STRATEGY);
        }

        String taskId = "task-" + UUID.randomUUID();
        RemediationTask task = RemediationTask.create(taskId, key, alert, strategy.get(), now);
        tasks.save(task);
        audit.record(AuditType.TASK_CREATED, taskId, alert.alertId(),
                DispositionReason.ACCEPTED.name(),
                Map.of("strategy", strategy.get().id(), "risk", strategy.get().riskLevel().name()));
        dispatcher.submit(taskId);
        log.info("[INTAKE] DECISION alert={} -> ACCEPTED task={} strategy={} risk={}",
                alert.alertId(), taskId, strategy.get().id(), strategy.get().riskLevel());
        return AlertDisposition.accepted(taskId);
    }
}
