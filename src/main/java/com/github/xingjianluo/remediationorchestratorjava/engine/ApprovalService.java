package com.github.xingjianluo.remediationorchestratorjava.engine;

import com.github.xingjianluo.remediationorchestratorjava.model.AuditType;
import com.github.xingjianluo.remediationorchestratorjava.model.RemediationTask;
import com.github.xingjianluo.remediationorchestratorjava.store.TaskStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 高风险修复的人工审批管理。四种结局彼此可区分且都是终态：
 * APPROVAL_GRANTED（批准后重新排队执行）、APPROVAL_REJECTED（拒绝）、
 * APPROVAL_TIMEOUT（超时未决）、CANCELLED（主动取消）。
 *
 * <p>超时检测使用独立单线程调度器；等待批准时阻塞工作线程的逻辑放在独立线程池，
 * 避免阻塞超时调度器。
 */
public class ApprovalService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalService.class);

    private final TaskStore tasks;
    private final AuditService audit;
    private final Clock clock;
    private final long defaultTimeoutMs;

    private final ConcurrentHashMap<String, ScheduledFuture<?>> timers = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "approval-timeout");
                t.setDaemon(true);
                return t;
            });

    private volatile TaskDispatcher dispatcher;

    public ApprovalService(TaskStore tasks, AuditService audit, Clock clock,
                           long defaultTimeoutMs) {
        this.tasks = tasks;
        this.audit = audit;
        this.clock = clock;
        this.defaultTimeoutMs = defaultTimeoutMs;
    }

    /** 打破与 TaskDispatcher 的循环依赖：配置装配后回填。 */
    public void bindDispatcher(TaskDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    /** 工作线程在 RUNNING 任务需要审批时调用：落库 WAITING_APPROVAL 并登记超时定时器。 */
    public void registerWaiting(String taskId, long timeoutMs) {
        Instant now = Instant.now(clock);
        RemediationTask task = require(taskId);
        Instant deadline = now.plusMillis(timeoutMs);
        task.markWaitingApproval(now, deadline);
        tasks.save(task);
        audit.record(AuditType.APPROVAL_WAITING, taskId, task.getAlertId(), null,
                Map.of("deadline", deadline.toString(), "timeoutMs", String.valueOf(timeoutMs)));
        log.info("[APPROVAL] task={} waiting for approval, deadline={}", taskId, deadline);

        ScheduledFuture<?> timer = scheduler.schedule(() -> resolveTimeout(taskId),
                timeoutMs, TimeUnit.MILLISECONDS);
        timers.put(taskId, timer);
    }

    /** 恢复场景：重启后 WAITING_APPROVAL 任务重新登记超时定时器，剩余时间按 deadline 计算。 */
    public void reregisterWaiting(RemediationTask task) {
        long remaining = defaultTimeoutMs;
        if (task.getDeadline() != null) {
            remaining = Math.max(1, task.getDeadline().toEpochMilli() - clock.millis());
        }
        ScheduledFuture<?> timer = scheduler.schedule(() -> resolveTimeout(task.getId()),
                remaining, TimeUnit.MILLISECONDS);
        timers.put(task.getId(), timer);
        log.info("[APPROVAL] re-registered waiting task={} remainingMs={}",
                task.getId(), remaining);
    }

    public synchronized void grant(String taskId) {
        ScheduledFuture<?> timer = timers.remove(taskId);
        if (timer != null) {
            timer.cancel(false);
        }
        RemediationTask task = require(taskId);
        task.approve(Instant.now(clock));
        tasks.save(task);
        audit.record(AuditType.APPROVAL_GRANTED, taskId, task.getAlertId(), null);
        log.info("[APPROVAL] task={} granted -> re-queue", taskId);
        dispatcher.onApproved(taskId);
    }

    public synchronized void reject(String taskId) {
        ScheduledFuture<?> timer = timers.remove(taskId);
        if (timer != null) {
            timer.cancel(false);
        }
        RemediationTask task = require(taskId);
        task.reject(Instant.now(clock));
        tasks.save(task);
        audit.record(AuditType.APPROVAL_REJECTED, taskId, task.getAlertId(), null);
        log.info("[APPROVAL] task={} rejected (terminal)", taskId);
        if (dispatcher != null) {
            dispatcher.onTaskSettled(taskId);
        }
    }

    public synchronized void cancel(String taskId) {
        ScheduledFuture<?> timer = timers.remove(taskId);
        if (timer != null) {
            timer.cancel(false);
        }
        RemediationTask task = require(taskId);
        task.cancel(Instant.now(clock));
        tasks.save(task);
        audit.record(AuditType.TASK_CANCELLED, taskId, task.getAlertId(), null);
        log.info("[APPROVAL] task={} cancelled (terminal)", taskId);
        if (dispatcher != null) {
            dispatcher.onTaskSettled(taskId);
        }
    }

    private synchronized void resolveTimeout(String taskId) {
        ScheduledFuture<?> timer = timers.remove(taskId);
        if (timer == null) {
            return; // 已被批准/拒绝/取消处理
        }
        RemediationTask task = require(taskId);
        if (task.getStatus().isTerminal() || task.isApprovalGranted()) {
            return;
        }
        task.timeoutApproval(Instant.now(clock));
        tasks.save(task);
        audit.record(AuditType.APPROVAL_TIMEOUT, taskId, task.getAlertId(), null);
        log.info("[APPROVAL] task={} approval timed out (terminal)", taskId);
        if (dispatcher != null) {
            dispatcher.onTaskSettled(taskId);
        }
    }

    private RemediationTask require(String taskId) {
        return tasks.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("task not found: " + taskId));
    }

    public Optional<RemediationTask> get(String taskId) {
        return tasks.findById(taskId);
    }

    public long waitingCount() {
        return timers.size();
    }

    public void shutdown() {
        scheduler.shutdownNow();
    }
}
