package com.github.xingjianluo.remediationorchestratorjava.engine;

import com.github.xingjianluo.remediationorchestratorjava.approval.ApprovalService;
import com.github.xingjianluo.remediationorchestratorjava.config.OrchestratorProperties;
import com.github.xingjianluo.remediationorchestratorjava.executor.StepExecutionResult;
import com.github.xingjianluo.remediationorchestratorjava.executor.StepExecutor;
import com.github.xingjianluo.remediationorchestratorjava.model.AuditEvent;
import com.github.xingjianluo.remediationorchestratorjava.model.AuditEvents;
import com.github.xingjianluo.remediationorchestratorjava.model.RemediationTask;
import com.github.xingjianluo.remediationorchestratorjava.model.StepState;
import com.github.xingjianluo.remediationorchestratorjava.model.TaskState;
import com.github.xingjianluo.remediationorchestratorjava.model.TaskStep;
import com.github.xingjianluo.remediationorchestratorjava.store.AuditLog;
import com.github.xingjianluo.remediationorchestratorjava.store.TaskStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 任务调度与执行。
 * <ul>
 *   <li>全局并发：固定大小线程池 = maxConcurrent，拿不到槽位的任务保持 QUEUED，不丢弃；</li>
 *   <li>同目标串行：维护运行中目标集合，同一目标同时只有一个任务在线程中；</li>
 *   <li>重试退避：失败后持久化 RETRY_WAIT + nextAttemptAt，线程内退避等待（崩溃后由恢复器重排队）；</li>
 *   <li>取消：每个步骤与退避等待前后检查 cancelRequested，取消时未做步骤置 CANCELLED，不留半完成态；</li>
 *   <li>关停：工作线程被中断时保留 RUNNING 落库状态，下次启动恢复，不写任何终态。</li>
 * </ul>
 */
public class TaskDispatcher implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(TaskDispatcher.class);

    private final TaskStore taskStore;
    private final AuditLog auditLog;
    private final StepExecutor stepExecutor;
    private final ApprovalService approvalService;
    private final RecoveryService recoveryService;
    private final OrchestratorProperties props;
    private final Clock clock;

    private ScheduledExecutorService scheduler;
    private ExecutorService lanePool;
    private final java.util.Set<String> runningTargets = ConcurrentHashMap.newKeySet();
    private final AtomicInteger active = new AtomicInteger();
    private volatile boolean running;

    public TaskDispatcher(TaskStore taskStore, AuditLog auditLog, StepExecutor stepExecutor,
                          ApprovalService approvalService, RecoveryService recoveryService,
                          OrchestratorProperties props, Clock clock) {
        this.taskStore = taskStore;
        this.auditLog = auditLog;
        this.stepExecutor = stepExecutor;
        this.approvalService = approvalService;
        this.recoveryService = recoveryService;
        this.props = props;
        this.clock = clock;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        // 先恢复再调度：重启后 RUNNING/RETRY_WAIT 任务被校正为 QUEUED。
        int resumed = recoveryService.recoverOnStartup();
        int poolSize = Math.max(1, props.getMaxConcurrent());
        lanePool = Executors.newFixedThreadPool(poolSize, r -> {
            Thread t = new Thread(r, "task-lane");
            t.setDaemon(true);
            return t;
        });
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "task-dispatcher");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::tick, props.getTickMillis(), props.getTickMillis(),
                TimeUnit.MILLISECONDS);
        running = true;
        log.info("[dispatcher] started: maxConcurrent={}, resumed={}, tickMillis={}",
                poolSize, resumed, props.getTickMillis());
    }

    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        if (lanePool != null) {
            lanePool.shutdownNow();
        }
        stepExecutor.shutdown();
        log.info("[dispatcher] stopped; interrupted tasks remain recoverable on next start");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** 一次调度扫描：处理审批超时，然后在并发上限内按入队顺序派发。 */
    void tick() {
        try {
            approvalService.sweepTimeouts(clock.millis());
            int capacity = props.getMaxConcurrent() - active.get();
            if (capacity <= 0) {
                return;
            }
            List<RemediationTask> ready = new ArrayList<>();
            ready.addAll(taskStore.findByState(TaskState.QUEUED));
            long now = clock.millis();
            taskStore.findByState(TaskState.RETRY_WAIT).stream()
                    .filter(t -> t.nextAttemptAt <= now)
                    .forEach(ready::add);
            ready.sort(Comparator.comparingLong(t -> t.queuedAt == 0 ? t.createdAt : t.queuedAt));

            for (RemediationTask task : ready) {
                if (active.get() >= props.getMaxConcurrent()) {
                    log.info("[tick] global concurrency cap {} reached; remaining tasks stay queued",
                            props.getMaxConcurrent());
                    return;
                }
                if (runningTargets.contains(task.target)) {
                    continue; // 同目标前一个任务尚未结束，保持排队
                }
                if (!claim(task)) {
                    continue;
                }
            }
        } catch (Exception e) {
            log.error("[tick] scheduling error", e);
        }
    }

    private boolean claim(RemediationTask stored) {
        // 双检：拿最新存储状态，避免与取消/恢复竞争。
        RemediationTask task = taskStore.findById(stored.id).orElse(null);
        if (task == null || task.state.isTerminal() || task.cancelRequested) {
            return false;
        }
        if (task.state != TaskState.QUEUED && task.state != TaskState.RETRY_WAIT) {
            return false;
        }
        if (!runningTargets.add(task.target)) {
            return false;
        }
        active.incrementAndGet();
        try {
            lanePool.submit(() -> runTask(task.id));
            return true;
        } catch (Exception e) {
            runningTargets.remove(task.target);
            active.decrementAndGet();
            log.error("[tick] failed to submit task {}", task.id, e);
            return false;
        }
    }

    private void runTask(String taskId) {
        boolean interruptedByShutdown = false;
        try {
            RemediationTask task = taskStore.findById(taskId).orElse(null);
            if (task == null || task.state.isTerminal()) {
                return;
            }
            long now = clock.millis();
            task.state = TaskState.RUNNING;
            if (task.startedAt == 0) {
                task.startedAt = now;
            }
            task.updatedAt = now;
            taskStore.save(task);
            audit(task, AuditEvents.TASK_DISPATCHED, Map.of("active", active.get()));
            log.info("[run] task={} target={} dispatched (active={})", task.id, task.target, active.get());

            for (TaskStep step : task.steps) {
                if (step.state == StepState.SUCCEEDED || step.state == StepState.SKIPPED) {
                    log.info("[run] task={} step={} already {}, not re-executed",
                            taskId, step.index, step.state);
                    continue;
                }
                StepOutcome outcome = runStepWithRetries(task, step);
                if (outcome == StepOutcome.CANCELLED) {
                    finishCancel(taskId);
                    return;
                }
                if (outcome == StepOutcome.INTERRUPTED) {
                    interruptedByShutdown = true;
                    return; // 保持 RUNNING 落库态，等待重启恢复
                }
                if (outcome == StepOutcome.FAILED) {
                    finishFailed(taskId);
                    return;
                }
            }
            finishSucceeded(taskId);
        } catch (Exception e) {
            log.error("[run] unexpected error task={}", taskId, e);
        } finally {
            runningTargets.remove(extractTarget(taskId));
            active.decrementAndGet();
            if (interruptedByShutdown) {
                log.info("[run] task={} left RUNNING for restart recovery", taskId);
            }
        }
    }

    private String extractTarget(String taskId) {
        return taskStore.findById(taskId).map(t -> t.target).orElse(null);
    }

    private StepOutcome runStepWithRetries(RemediationTask task, TaskStep step) {
        int maxRetries = task.maxRetries >= 0 ? task.maxRetries : props.getDefaultMaxRetries();
        long backoffBase = task.backoffBaseMillis >= 0 ? task.backoffBaseMillis
                : props.getDefaultBackoffBaseMillis();
        long timeout = task.stepTimeoutMillis >= 0 ? task.stepTimeoutMillis
                : props.getDefaultStepTimeoutMillis();

        int attempt = 0; // 0 = 首次执行；1..maxRetries 为重试
        while (attempt <= maxRetries) {
            RemediationTask fresh = taskStore.findById(task.id).orElseThrow();
            if (fresh.cancelRequested) {
                return StepOutcome.CANCELLED;
            }
            task = fresh;
            // 必须操作持久化副本中的步骤实例，否则状态修改不会落库
            step = task.steps.get(step.index);

            step.state = StepState.RUNNING;
            step.attempts = attempt + 1;
            step.updatedAt = clock.millis();
            task.state = TaskState.RUNNING;
            task.updatedAt = clock.millis();
            taskStore.save(task);
            audit(task, AuditEvents.STEP_STARTED, Map.of(
                    "step", step.index,
                    "name", step.name,
                    "type", step.type.name(),
                    "attempt", attempt + 1,
                    "maxRetries", maxRetries));
            log.info("[run] task={} step={} attempt={}/{} started",
                    task.id, step.index, attempt + 1, maxRetries + 1);

            StepExecutionResult result = stepExecutor.execute(task, step, timeout);

            if (result.success()) {
                step.state = StepState.SUCCEEDED;
                step.lastError = null;
                step.updatedAt = clock.millis();
                task.updatedAt = clock.millis();
                taskStore.save(task);
                audit(task, AuditEvents.STEP_SUCCEEDED, Map.of(
                        "step", step.index, "attempt", attempt + 1));
                log.info("[run] task={} step={} succeeded on attempt {}",
                        task.id, step.index, attempt + 1);
                return StepOutcome.SUCCEEDED;
            }

            step.lastError = result.error();
            step.updatedAt = clock.millis();
            boolean interrupt = "interrupted".equals(result.error());
            RemediationTask latest = taskStore.findById(task.id).orElseThrow();
            boolean cancelRequested = latest.cancelRequested;
            audit(task, AuditEvents.STEP_FAILED, Map.of(
                    "step", step.index,
                    "attempt", attempt + 1,
                    "error", String.valueOf(result.error()),
                    "interrupted", interrupt,
                    "cancelRequested", cancelRequested));
            log.warn("[run] task={} step={} attempt={} failed: {}",
                    task.id, step.index, attempt + 1, result.error());

            if (interrupt) {
                if (cancelRequested) {
                    return StepOutcome.CANCELLED;
                }
                // 非取消的中断只可能来自关停：恢复步骤为 PENDING，保持任务 RUNNING 供重启恢复。
                step.state = StepState.PENDING;
                task.updatedAt = clock.millis();
                taskStore.save(task);
                return StepOutcome.INTERRUPTED;
            }

            if (cancelRequested) {
                return StepOutcome.CANCELLED;
            }

            if (attempt < maxRetries) {
                long delay = backoffBase * (attempt + 1);
                step.state = StepState.PENDING;
                task.state = TaskState.RETRY_WAIT;
                task.nextAttemptAt = clock.millis() + delay;
                task.updatedAt = clock.millis();
                taskStore.save(task);
                audit(task, AuditEvents.STEP_RETRY_SCHEDULED, Map.of(
                        "step", step.index,
                        "nextAttempt", attempt + 2,
                        "backoffMillis", delay,
                        "nextAttemptAt", task.nextAttemptAt));
                log.info("[run] task={} step={} retry {} in {}ms", task.id, step.index, attempt + 2, delay);
                if (sleepBackoff(task.id, delay)) {
                    return StepOutcome.CANCELLED;
                }
                task.state = TaskState.RUNNING;
                task.nextAttemptAt = 0;
                task.updatedAt = clock.millis();
                taskStore.save(task);
            } else {
                step.state = StepState.FAILED;
                task.updatedAt = clock.millis();
                taskStore.save(task);
                return StepOutcome.FAILED;
            }
            attempt++;
        }
        return StepOutcome.FAILED;
    }

    /**
     * 退避等待。注意：线程睡眠基于真实墙钟时间，因此循环截止判定使用 System.currentTimeMillis()；
     * 持久化字段 nextAttemptAt 仍使用注入时钟，以保证重启恢复语义可测。
     *
     * @return true 表示退避期间收到取消请求或关停中断。
     */
    private boolean sleepBackoff(String taskId, long delayMillis) {
        long deadline = System.currentTimeMillis() + delayMillis;
        while (true) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                return false;
            }
            try {
                Thread.sleep(Math.min(50, remaining));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return true;
            }
            RemediationTask latest = taskStore.findById(taskId).orElse(null);
            if (latest == null || latest.cancelRequested || !running) {
                return true;
            }
        }
    }

    private void finishSucceeded(String taskId) {
        RemediationTask task = taskStore.findById(taskId).orElseThrow();
        task.state = TaskState.SUCCEEDED;
        task.terminalReason = null;
        task.finishedAt = clock.millis();
        task.updatedAt = clock.millis();
        taskStore.save(task);
        audit(task, AuditEvents.TASK_SUCCEEDED, Map.of("steps", task.steps.size()));
        log.info("[run] task={} SUCCEEDED", taskId);
    }

    private void finishFailed(String taskId) {
        RemediationTask task = taskStore.findById(taskId).orElseThrow();
        TaskStep failed = task.steps.stream()
                .filter(s -> s.state == StepState.FAILED)
                .findFirst().orElse(null);
        String reason = failed == null ? "unknown"
                : "step " + failed.index + " (" + failed.name + ") failed after "
                + failed.attempts + " attempts: " + failed.lastError;
        task.state = TaskState.FAILED;
        task.terminalReason = reason;
        task.lastError = reason;
        task.finishedAt = clock.millis();
        task.updatedAt = clock.millis();
        taskStore.save(task);
        audit(task, AuditEvents.TASK_FAILED, Map.of(
                "failedStep", failed == null ? -1 : failed.index,
                "attempts", failed == null ? 0 : failed.attempts,
                "error", failed == null ? "" : String.valueOf(failed.lastError),
                "reason", reason));
        log.warn("[run] task={} FAILED: {}", taskId, reason);
    }

    private void finishCancel(String taskId) {
        RemediationTask task = taskStore.findById(taskId).orElseThrow();
        for (TaskStep step : task.steps) {
            if (step.state != StepState.SUCCEEDED) {
                step.state = StepState.CANCELLED;
                step.updatedAt = clock.millis();
            }
        }
        task.state = TaskState.CANCELLED;
        task.terminalReason = "cancelled: " + safe(task.terminalReason);
        task.finishedAt = clock.millis();
        task.updatedAt = clock.millis();
        taskStore.save(task);
        audit(task, AuditEvents.TASK_CANCELLED, Map.of("stage", "execution"));
        log.info("[run] task={} CANCELLED; incomplete steps marked CANCELLED", taskId);
    }

    private void audit(RemediationTask task, String event, Map<String, Object> detail) {
        auditLog.append(new AuditEvent(clock.millis(), event, task.id,
                task.alert != null ? task.alert.id() : null, detail));
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private enum StepOutcome {
        SUCCEEDED, FAILED, CANCELLED, INTERRUPTED
    }
}
