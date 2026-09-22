package com.github.xingjianluo.remediationorchestratorjava.support;

import com.github.xingjianluo.remediationorchestratorjava.action.ActionResult;
import com.github.xingjianluo.remediationorchestratorjava.action.RemediationAction;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 测试用可编排动作。阻塞门按"步骤+任务"维度独立（{@link #blockOn(String)} 之后，
 * 每个进入该步骤的任务各自有一个门），避免不同任务共享同一个闩锁造成串扰。
 */
public class RecordingAction implements RemediationAction {

    private final String name;
    private final Map<String, AtomicInteger> counts = new ConcurrentHashMap<>();
    private final Map<String, Integer> failFirst = new ConcurrentHashMap<>();
    private final Map<String, String> failMessages = new ConcurrentHashMap<>();
    private final Map<String, CountDownLatch> entered = new ConcurrentHashMap<>();
    private final Map<String, CountDownLatch> release = new ConcurrentHashMap<>();
    private final List<String> blockedSteps = new CopyOnWriteArrayList<>();
    private final List<String> executionLog = new CopyOnWriteArrayList<>();

    public RecordingAction(String name) {
        this.name = name;
    }

    @Override
    public String name() {
        return name;
    }

    public void failFirstTimes(String stepName, int times, String message) {
        failFirst.put(stepName, times);
        failMessages.put(stepName, message);
    }

    public void blockOn(String stepName) {
        blockedSteps.add(stepName);
    }

    private CountDownLatch gate(String step, String task, Map<String, CountDownLatch> map) {
        return map.computeIfAbsent(step + "@" + task, k -> new CountDownLatch(1));
    }

    /** 等待 n 个不同任务进入该步骤（各自闩锁仍保持阻塞，直到 releaseAll）。 */
    public boolean awaitNEntered(String stepName, int n, long ms) throws InterruptedException {
        long deadline = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < deadline) {
            long distinct = entered.keySet().stream()
                    .filter(k -> k.startsWith(stepName + "@"))
                    .filter(k -> entered.get(k).getCount() == 0)
                    .count();
            if (distinct >= n) {
                return true;
            }
            Thread.sleep(10);
        }
        return false;
    }

    public void release(String stepName, String taskId) {
        gate(stepName, taskId, release).countDown();
    }

    /** 释放当前所有进入该步骤的任务，并令该步骤之后不再阻塞。 */
    public void releaseAll(String stepName) {
        for (Map.Entry<String, CountDownLatch> e : release.entrySet()) {
            if (e.getKey().startsWith(stepName + "@")) {
                e.getValue().countDown();
            }
        }
        blockedSteps.remove(stepName);
    }

    public int countOf(String stepName) {
        AtomicInteger c = counts.get(stepName);
        return c == null ? 0 : c.get();
    }

    public List<String> executionLog() {
        return List.copyOf(executionLog);
    }

    /** 模拟进程重启：清除遗留的阻塞门与门闩（新进程里不存在旧的测试阻塞）。 */
    public void clearBlocks() {
        blockedSteps.clear();
        entered.clear();
        release.clear();
    }

    @Override
    public ActionResult execute(Context context) throws Exception {
        String step = context.stepName();
        int n = counts.computeIfAbsent(step, k -> new AtomicInteger()).incrementAndGet();
        executionLog.add(step + "#" + n + "@" + context.taskId());

        if (blockedSteps.contains(step)) {
            gate(step, context.taskId(), entered).countDown();
            // 进程“被杀”时会中断该等待：必须让中断传播出去（await 已恢复中断标志），
            // 使旧工作线程真正终止，而不是继续执行后续步骤。
            gate(step, context.taskId(), release).await(10, TimeUnit.SECONDS);
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("interrupted while blocked in test");
            }
        }

        Integer failTimes = failFirst.get(step);
        if (failTimes != null && n <= failTimes) {
            return ActionResult.fail(failMessages.getOrDefault(step, "injected failure"));
        }
        return ActionResult.ok();
    }
}
