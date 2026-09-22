package com.github.xingjianluo.remediationorchestratorjava.engine;

import com.github.xingjianluo.remediationorchestratorjava.model.AuditType;
import com.github.xingjianluo.remediationorchestratorjava.model.RemediationTask;
import com.github.xingjianluo.remediationorchestratorjava.model.TaskStatus;
import com.github.xingjianluo.remediationorchestratorjava.store.TaskStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 启动恢复：扫描所有非终态任务并重建调度。
 * <ul>
 *   <li>RUNNING（含执行到一半/进程被杀）：markRecovered 后重新排队，
 *       已 SUCCEEDED 步骤保持不变，恢复后从下一个步骤继续，绝不重复执行；</li>
 *   <li>QUEUED：直接重新入队；PENDING：迁移到 QUEUED 后入队；</li>
 *   <li>WAITING_APPROVAL：重新登记审批超时定时器，审批结论仍可区分。</li>
 * </ul>
 */
public class RecoveryService {

    private static final Logger log = LoggerFactory.getLogger(RecoveryService.class);

    private final TaskStore tasks;
    private final TaskDispatcher dispatcher;
    private final ApprovalService approvals;
    private final AuditService audit;
    private final Clock clock;

    public RecoveryService(TaskStore tasks, TaskDispatcher dispatcher,
                           ApprovalService approvals, AuditService audit, Clock clock) {
        this.tasks = tasks;
        this.dispatcher = dispatcher;
        this.approvals = approvals;
        this.audit = audit;
        this.clock = clock;
    }

    public int recoverAll() {
        List<RemediationTask> pending = tasks.findNonTerminal();
        log.info("[RECOVER] scan found {} non-terminal task(s)", pending.size());
        int recovered = 0;
        for (RemediationTask task : pending) {
            TaskStatus from = task.getStatus();
            switch (from) {
                case WAITING_APPROVAL -> {
                    approvals.reregisterWaiting(task);
                    audit.record(AuditType.TASK_RECOVERED, task.getId(), task.getAlertId(),
                            "re-registered waiting approval",
                            Map.of("from", from.name()));
                    log.info("[RECOVER] task={} re-registered WAITING_APPROVAL", task.getId());
                }
                case QUEUED, PENDING -> {
                    dispatcher.submit(task.getId());
                    audit.record(AuditType.TASK_RECOVERED, task.getId(), task.getAlertId(),
                            "re-queued", Map.of("from", from.name()));
                    log.info("[RECOVER] task={} re-queued from {}", task.getId(), from);
                }
                case RUNNING -> {
                    long succeeded = task.getSteps().stream()
                            .filter(s -> s.getStatus()
                                    == com.github.xingjianluo.remediationorchestratorjava.model.StepStatus.SUCCEEDED)
                            .count();
                    task.markRecovered(Instant.now(clock));
                    tasks.save(task);
                    audit.record(AuditType.TASK_RECOVERED, task.getId(), task.getAlertId(),
                            "resumed from running",
                            Map.of("completedSteps", String.valueOf(succeeded)));
                    log.info("[RECOVER] task={} resumed from RUNNING; {} step(s) already "
                            + "succeeded will not re-run", task.getId(), succeeded);
                    dispatcher.submit(task.getId());
                }
                default -> log.warn("[RECOVER] task={} in unexpected non-terminal state {}",
                        task.getId(), from);
            }
            recovered++;
        }
        return recovered;
    }
}
