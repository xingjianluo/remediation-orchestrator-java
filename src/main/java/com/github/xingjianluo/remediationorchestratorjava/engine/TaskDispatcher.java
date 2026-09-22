package com.github.xingjianluo.remediationorchestratorjava.engine;

import com.github.xingjianluo.remediationorchestratorjava.model.AuditType;
import com.github.xingjianluo.remediationorchestratorjava.model.RemediationTask;
import com.github.xingjianluo.remediationorchestratorjava.model.RiskLevel;
import com.github.xingjianluo.remediationorchestratorjava.model.TaskStatus;
import com.github.xingjianluo.remediationorchestratorjava.store.TaskStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 任务调度器，维护内存中的调度不变量（持久化由各服务完成）：
 * <ul>
 *   <li>FIFO 等待队列：全局并发达到上限时任务排队而非丢弃；</li>
 *   <li>同一 target 串行：集合 busyTargets 记录正在运行的目标，后续任务继续等待；</li>
 *   <li>高风险任务在实际执行前先进入 WAITING_APPROVAL（审批等待不占用全局并发名额）；</li>
 *   <li>批准后重新入队；拒绝/超时/取消释放目标与名额。</li>
 * </ul>
 */
public class TaskDispatcher {

    private static final Logger log = LoggerFactory.getLogger(TaskDispatcher.class);

    private final TaskStore tasks;
    private final TaskExecutor executor;
    private final ApprovalService approvals;
    private final AuditService audit;
    private final Clock clock;
    private final long approvalTimeoutMs;

    private final Semaphore permits;
    private final Deque<String> queue = new ArrayDeque<>();
    private final Set<String> busyTargets = new HashSet<>();
    private final Object lock = new Object();
    private final ExecutorService pool;
    private final String instanceId = Integer.toHexString(System.identityHashCode(this));

    public TaskDispatcher(TaskStore tasks, TaskExecutor executor, ApprovalService approvals,
                          AuditService audit, Clock clock, int globalConcurrency,
                          long approvalTimeoutMs) {
        this.tasks = tasks;
        this.executor = executor;
        this.approvals = approvals;
        this.audit = audit;
        this.clock = clock;
        this.approvalTimeoutMs = approvalTimeoutMs;
        this.permits = new Semaphore(globalConcurrency);
        this.pool = Executors.newFixedThreadPool(globalConcurrency, r -> {
            Thread t = new Thread(r, "worker-" + instanceId);
            t.setDaemon(true);
            return t;
        });
    }

    /** 新任务或恢复任务入队（PENDING -> QUEUED），随后尝试调度。 */
    public void submit(String taskId) {
        synchronized (lock) {
            RemediationTask task = tasks.findById(taskId).orElse(null);
            if (task == null) {
                return;
            }
            if (task.getStatus() == TaskStatus.PENDING) {
                task.markQueued(Instant.now(clock));
                tasks.save(task);
                audit.record(AuditType.TASK_QUEUED, taskId, task.getAlertId(),
                        "queued for execution");
            }
            queue.addLast(taskId);
            log.info("[DISPATCH] task={} enqueued, queueDepth={}", taskId, queue.size());
        }
        pump();
    }

    /** 批准后重新入队（状态已是 QUEUED）。 */
    public void onApproved(String taskId) {
        synchronized (lock) {
            queue.addFirst(taskId);
            log.info("[DISPATCH] approved task={} re-queued at head", taskId);
        }
        pump();
    }

    /** 审批终态（拒绝/超时/取消）后驱动调度。 */
    public void onTaskSettled(String taskId) {
        log.info("[DISPATCH] task={} settled externally, pumping", taskId);
        pump();
    }

    private void pump() {
        while (true) {
            String taskId;
            synchronized (lock) {
                cleanStale();
                if (queue.isEmpty() || permits.availablePermits() == 0) {
                    return;
                }
                String candidate = null;
                Iterator<String> it = queue.iterator();
                while (it.hasNext()) {
                    String id = it.next();
                    RemediationTask t = tasks.findById(id).orElse(null);
                    if (t == null) {
                        it.remove();
                        continue;
                    }
                    if (!busyTargets.contains(t.getTarget())) {
                        candidate = id;
                        it.remove();
                        break;
                    }
                }
                if (candidate == null) {
                    return;
                }
                RemediationTask task = tasks.findById(candidate).orElseThrow();
                busyTargets.add(task.getTarget());
                permits.acquireUninterruptibly();
                taskId = candidate;
            }
            dispatch(taskId);
        }
    }

    /** 移除等待期间已不存在或已离开 QUEUED 的过期条目（如排队中被取消）。 */
    private void cleanStale() {
        queue.removeIf(id -> {
            Optional<RemediationTask> t = tasks.findById(id);
            return t.isEmpty() || t.get().getStatus() != TaskStatus.QUEUED;
        });
    }

    private void dispatch(String taskId) {
        pool.submit(() -> {
            // 新任务在一个干净的中断状态下开始（线程可能复用过，带旧的中断标志；
            // 真实新进程里线程不存在遗留中断）。
            Thread.interrupted();
            boolean releaseTarget = true;
            boolean releasePermit = true;
            try {
                RemediationTask task = tasks.findById(taskId).orElseThrow();
                if (task.getRiskLevel() == RiskLevel.HIGH && !task.isApprovalGranted()) {
                    // 高风险且尚未批准：进入审批等待，不占全局并发名额
                    permits.release();
                    releasePermit = false;
                    busyTargets.remove(task.getTarget());
                    releaseTarget = false;
                    approvals.registerWaiting(taskId, approvalTimeoutMs);
                    return;
                }
                boolean terminal = executor.execute(taskId);
                log.info("[DISPATCH] worker finished task={} terminal={}", taskId, terminal);
            } catch (TaskAbortedException aborted) {
                // 模拟崩溃：保留 RUNNING 快照，不释放（交给重启恢复重建调度不变量）
                releaseTarget = false;
                releasePermit = false;
                log.warn("[DISPATCH] worker aborted task={}; awaiting recovery", taskId);
            } catch (Throwable t) {
                log.error("[DISPATCH] worker error for task={}", taskId, t);
            } finally {
                if (releaseTarget) {
                    RemediationTask t = tasks.findById(taskId).orElse(null);
                    if (t != null) {
                        busyTargets.remove(t.getTarget());
                    }
                }
                if (releasePermit) {
                    permits.release();
                }
            }
            pump();
        });
    }

    public int queueDepth() {
        synchronized (lock) {
            return queue.size();
        }
    }

    public int activeCount() {
        synchronized (lock) {
            return busyTargets.size();
        }
    }

    public void shutdown() {
        pool.shutdownNow();
        try {
            if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("[DISPATCH] worker pool did not terminate in 5s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // 测试/诊断可见
    List<String> queuedIds() {
        synchronized (lock) {
            return List.copyOf(queue);
        }
    }
}
