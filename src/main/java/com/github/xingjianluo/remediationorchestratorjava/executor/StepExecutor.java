package com.github.xingjianluo.remediationorchestratorjava.executor;

import com.github.xingjianluo.remediationorchestratorjava.model.RemediationTask;
import com.github.xingjianluo.remediationorchestratorjava.model.StepType;
import com.github.xingjianluo.remediationorchestratorjava.model.TaskStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 在独立线程中执行步骤，通过 Future 实施单步骤超时；
 * 超时或任务取消时中断工作线程，SLOW 等可响应中断的动作会立即停止，不产生半完成状态。
 */
public class StepExecutor {

    private static final Logger log = LoggerFactory.getLogger(StepExecutor.class);

    private final Map<StepType, StepHandler> handlers;
    private final ExecutorService workers = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "step-worker");
        t.setDaemon(true);
        return t;
    });

    public StepExecutor(Map<StepType, StepHandler> handlers) {
        this.handlers = handlers;
    }

    public StepExecutionResult execute(RemediationTask task, TaskStep step, long timeoutMillis) {
        StepHandler handler = handlers.get(step.type);
        if (handler == null) {
            return StepExecutionResult.fail("no handler for step type " + step.type);
        }
        Future<StepExecutionResult> future = workers.submit(() -> handler.execute(step, task));
        try {
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("[step-exec] timeout after {}ms task={} step={}, interrupted",
                    timeoutMillis, task.id, step.index);
            return StepExecutionResult.fail("step timeout after " + timeoutMillis + "ms");
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            log.info("[step-exec] dispatcher interrupted task={} step={}", task.id, step.index);
            return StepExecutionResult.fail("interrupted");
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof InterruptedException) {
                return StepExecutionResult.fail("interrupted");
            }
            log.warn("[step-exec] handler threw task={} step={}: {}", task.id, step.index, cause);
            return StepExecutionResult.fail("handler error: " + cause);
        }
    }

    public void shutdown() {
        workers.shutdownNow();
    }
}
