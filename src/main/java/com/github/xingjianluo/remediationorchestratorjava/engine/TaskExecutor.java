package com.github.xingjianluo.remediationorchestratorjava.engine;

import com.github.xingjianluo.remediationorchestratorjava.action.ActionResult;
import com.github.xingjianluo.remediationorchestratorjava.action.RemediationAction;
import com.github.xingjianluo.remediationorchestratorjava.model.AuditType;
import com.github.xingjianluo.remediationorchestratorjava.model.RemediationTask;
import com.github.xingjianluo.remediationorchestratorjava.model.StepStatus;
import com.github.xingjianluo.remediationorchestratorjava.model.TaskStep;
import com.github.xingjianluo.remediationorchestratorjava.store.TaskStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * 修复执行器。要点：
 * <ul>
 *   <li>步骤严格顺序执行；已 SUCCEEDED 的步骤直接跳过，恢复后绝不重复执行；</li>
 *   <li>每个步骤最多尝试 maxAttempts 次，失败间固定退避 retryBackoffMs；</li>
 *   <li>单次执行受 step.timeoutMs 约束，超时中断执行线程并计为一次失败尝试；</li>
 *   <li>每次状态变化都原子持久化并写审计；最终失败时记录足够定位信息（步骤、尝试次数、末次错误）；</li>
 *   <li>工作线程被中断（模拟进程崩溃）时不做终态迁移，保留 RUNNING 快照供重启恢复。</li>
 * </ul>
 */
public class TaskExecutor {

    private static final Logger log = LoggerFactory.getLogger(TaskExecutor.class);

    private final TaskStore tasks;
    private final AuditService audit;
    private final ApprovalService approvals;
    private final Clock clock;
    private final Map<String, RemediationAction> actions;

    public TaskExecutor(TaskStore tasks, AuditService audit, ApprovalService approvals,
                        Clock clock, Map<String, RemediationAction> actions) {
        this.tasks = tasks;
        this.audit = audit;
        this.approvals = approvals;
        this.clock = clock;
        this.actions = actions;
    }

    /**
     * 由调度器工作线程调用。返回 true 表示任务进入终态（成功/失败/审批终态）；
     * 抛出 {@link TaskAbortedException} 表示线程被中断，任务保持可恢复状态。
     */
    public boolean execute(String taskId) {
        RemediationTask task = load(taskId);
        task.markRunning(Instant.now(clock));
        persist(task, AuditType.TASK_RUNNING, "task running");
        log.info("[EXEC] task={} target={} strategy={} running (version={})",
                taskId, task.getTarget(), task.getStrategyId(), task.getVersion());

        try {
            for (TaskStep step : task.getSteps()) {
                if (step.getStatus() == StepStatus.SUCCEEDED) {
                    log.info("[EXEC] task={} step={} already succeeded, skip", taskId, step.getName());
                    continue;
                }
                boolean ok = runStepWithRetry(task, step);
                if (!ok) {
                    String summary = String.format(
                            "step '%s' failed after %d attempt(s), lastError=%s",
                            step.getName(), step.getAttempts(), step.getLastError());
                    task.markFailed(Instant.now(clock), summary);
                    tasks.save(task);
                    audit.record(AuditType.TASK_FAILED, taskId, task.getAlertId(), summary);
                    log.warn("[EXEC] task={} FAILED: {}", taskId, summary);
                    return true;
                }
            }
            task.markSucceeded(Instant.now(clock));
            tasks.save(task);
            audit.record(AuditType.TASK_SUCCEEDED, taskId, task.getAlertId(), null);
            log.info("[EXEC] task={} SUCCEEDED (merged duplicates={})",
                    taskId, task.getMergedDuplicateCount());
            return true;
        } catch (TaskAbortedException e) {
            log.warn("[EXEC] task={} aborted by interrupt; left recoverable in RUNNING", taskId);
            throw e;
        }
    }

    private boolean runStepWithRetry(RemediationTask task, TaskStep step) {
        String taskId = task.getId();
        RemediationAction action = actions.get(step.getActionName());
        if (action == null) {
            step.setLastError("no such action bean: " + step.getActionName());
            step.setStatus(StepStatus.FAILED);
            tasks.save(task);
            audit.record(AuditType.STEP_FAILED, taskId, task.getAlertId(), step.getLastError(),
                    Map.of("step", step.getName()));
            return false;
        }

        while (step.getAttempts() < task.getMaxAttempts()) {
            if (Thread.currentThread().isInterrupted()) {
                throw new TaskAbortedException(taskId);
            }
            int attempt = step.getAttempts() + 1;
            step.setAttempts(attempt);
            step.setStatus(StepStatus.RUNNING);
            if (attempt == 1) {
                audit.record(AuditType.STEP_START, taskId, task.getAlertId(), null,
                        Map.of("step", step.getName(), "action", step.getActionName()));
            } else {
                audit.record(AuditType.STEP_RETRY, taskId, task.getAlertId(), null,
                        Map.of("step", step.getName(), "attempt", String.valueOf(attempt)));
            }
            tasks.save(task);
            log.info("[EXEC] task={} step={} attempt={}/{} action={}",
                    taskId, step.getName(), attempt, task.getMaxAttempts(), step.getActionName());

            String error = invokeOnce(action, task, step);
            if (error == null) {
                step.setStatus(StepStatus.SUCCEEDED);
                step.setLastError(null);
                tasks.save(task);
                audit.record(AuditType.STEP_SUCCEEDED, taskId, task.getAlertId(), null,
                        Map.of("step", step.getName(), "attempts", String.valueOf(attempt)));
                log.info("[EXEC] task={} step={} SUCCEEDED after {} attempt(s)",
                        taskId, step.getName(), attempt);
                return true;
            }

            step.setLastError(error);
            log.warn("[EXEC] task={} step={} attempt={} failed: {} (thread={} interrupted={})",
                    taskId, step.getName(), attempt, error,
                    Thread.currentThread().getName(), Thread.currentThread().isInterrupted());
            if (attempt >= task.getMaxAttempts()) {
                step.setStatus(StepStatus.FAILED);
                tasks.save(task);
                audit.record(AuditType.STEP_FAILED, taskId, task.getAlertId(), error,
                        Map.of("step", step.getName(), "attempts", String.valueOf(attempt)));
                return false;
            }
            tasks.save(task);
            sleepBackoff(task.getRetryBackoffMs(), taskId);
        }
        return false;
    }

    /** 单次步骤调用的结果承载。 */
    private static final class StepOutcome {
        volatile String error;
        volatile Throwable thrown;
    }

    /**
     * @return null 表示成功，否则返回错误原因（超时也转为错误描述）。
     * 每次调用都在一个全新的守护线程中执行动作，用后即弃，绝不复用可能带中断标志的线程。
     */
    private String invokeOnce(RemediationAction action, RemediationTask task, TaskStep step) {
        RemediationAction.Context ctx = RemediationAction.Context.of(
                task.getId(), task.getTarget(), task.getAlertId(), step);
        StepOutcome outcome = new StepOutcome();
        Thread runner = new Thread(() -> {
            try {
                ActionResult r = action.execute(ctx);
                if (r == null) {
                    outcome.error = "action returned null result";
                } else if (!r.success()) {
                    outcome.error = "action returned failure: " + r.error();
                }
            } catch (Throwable t) {
                outcome.thrown = t;
            }
        }, "step-runner-" + task.getId() + "-" + step.getName());
        runner.setDaemon(true);
        runner.start();
        try {
            runner.join(step.getTimeoutMs());
            if (runner.isAlive()) {
                runner.interrupt();
                runner.join(1_000);
                audit.record(AuditType.STEP_TIMEOUT, task.getId(), task.getAlertId(), null,
                        Map.of("step", step.getName(),
                                "timeoutMs", String.valueOf(step.getTimeoutMs())));
                return "timeout after " + step.getTimeoutMs() + "ms";
            }
        } catch (InterruptedException ie) {
            runner.interrupt();
            Thread.currentThread().interrupt();
            throw new TaskAbortedException(task.getId());
        }

        if (outcome.thrown != null) {
            Throwable t = outcome.thrown;
            if (t instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                throw new TaskAbortedException(task.getId());
            }
            log.warn("[EXEC] action threw for task={} step={}",
                    task.getId(), step.getName(), t);
            String msg = t.getMessage();
            return t.getClass().getSimpleName() + ": " + (msg == null ? "(no message)" : msg);
        }
        return outcome.error;
    }

    private void sleepBackoff(long backoffMs, String taskId) {
        try {
            Thread.sleep(backoffMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TaskAbortedException(taskId);
        }
    }

    private RemediationTask load(String taskId) {
        return tasks.findById(taskId)
                .orElseThrow(() -> new IllegalStateException("task missing: " + taskId));
    }

    private void persist(RemediationTask task, AuditType type, String reason) {
        tasks.save(task);
        audit.record(type, task.getId(), task.getAlertId(), reason);
    }

    public void shutdown() {
        // 步骤执行线程为用后即弃的守护线程，无需关闭线程池。
    }
}
