package com.github.xingjianluo.remediationorchestratorjava.tests;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.xingjianluo.remediationorchestratorjava.approval.ApprovalService;
import com.github.xingjianluo.remediationorchestratorjava.config.OrchestratorProperties;
import com.github.xingjianluo.remediationorchestratorjava.engine.PolicyMatcher;
import com.github.xingjianluo.remediationorchestratorjava.engine.RecoveryService;
import com.github.xingjianluo.remediationorchestratorjava.engine.TaskDispatcher;
import com.github.xingjianluo.remediationorchestratorjava.engine.TaskEngine;
import com.github.xingjianluo.remediationorchestratorjava.executor.LocalStepHandlers;
import com.github.xingjianluo.remediationorchestratorjava.executor.StepExecutor;
import com.github.xingjianluo.remediationorchestratorjava.maintenance.MaintenanceWindowService;
import com.github.xingjianluo.remediationorchestratorjava.model.*;
import com.github.xingjianluo.remediationorchestratorjava.recurrence.RecurrenceService;
import com.github.xingjianluo.remediationorchestratorjava.store.FileAuditLog;
import com.github.xingjianluo.remediationorchestratorjava.store.FileTaskStore;
import com.github.xingjianluo.remediationorchestratorjava.store.InMemoryRecurrenceStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;

import static com.github.xingjianluo.remediationorchestratorjava.tests.TestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 优雅关停恰好落在“退避等待重试 / 排队等执行”期间时的恢复语义（真实调度、真实文件存储、真实重启）：
 * <ul>
 *   <li>关停打断不算取消：RETRY_WAIT / QUEUED 任务保持非终态落库，绝不写 CANCELLED/FAILED；</li>
 *   <li>重启后按原安排继续：该重试的继续重试（已累计尝试次数不清零）、该排队的继续排队；</li>
 *   <li>已成功步骤的副作用不重复；等待重试中的修复不被复发/去重判定当成“已结束”；</li>
 *   <li>关停与主动取消同时发生：以持久化 cancelRequested 为准，取消优先，结论唯一。</li>
 * </ul>
 */
class ShutdownDuringRetryWaitTest {

    private static final Logger log = LoggerFactory.getLogger(ShutdownDuringRetryWaitTest.class);

    @TempDir
    Path tempDir;

    /** 一套“进程”：文件存储 + 真实调度器。重启 = 在同一目录上 new 一套。 */
    record Stack(FileTaskStore store, FileAuditLog audit, TaskEngine engine, TaskDispatcher dispatcher) {
    }

    private Stack newStack(List<RemediationPolicy> policies, int maxConcurrent) {
        OrchestratorProperties props = new OrchestratorProperties();
        props.setMaxConcurrent(maxConcurrent);
        props.setTickMillis(20);
        props.setDefaultMaxRetries(2);
        props.setDefaultBackoffBaseMillis(50);
        props.setDefaultStepTimeoutMillis(1000);
        props.setDefaultApprovalTimeoutSeconds(60);
        ObjectMapper mapper = new ObjectMapper();
        FileTaskStore store = new FileTaskStore(tempDir.resolve("tasks"), mapper);
        store.init();
        FileAuditLog audit = new FileAuditLog(tempDir.resolve("audit.jsonl"), mapper);
        audit.init();
        Clock clock = Clock.systemUTC();
        ApprovalService approvals = new ApprovalService(store, audit, props, clock);
        RecurrenceService recurrence = new RecurrenceService(new InMemoryRecurrenceStore(), audit, props, clock);
        TaskEngine engine = new TaskEngine(store, audit, new PolicyMatcher(policies),
                new MaintenanceWindowService(), props, clock, recurrence);
        engine.setApprovalService(approvals);
        StepExecutor executor = new StepExecutor(LocalStepHandlers.defaults(tempDir));
        RecoveryService recovery = new RecoveryService(store, audit, clock);
        TaskDispatcher dispatcher = new TaskDispatcher(store, audit, executor, approvals, recovery, props, clock);
        return new Stack(store, audit, engine, dispatcher);
    }

    /** step0 MARKER（可观测副作用）+ step1 FLAKY 先败 1 次；退避 500ms 留出关停窗口。 */
    private RemediationPolicy retryPolicy() {
        RemediationPolicy p = new RemediationPolicy();
        p.id = "p-retry";
        p.matchAnyKeywords = List.of("disk");
        p.maxRetries = 2;
        p.backoffBaseMillis = 500;
        p.stepTimeoutMillis = 1000;
        p.steps = List.of(
                step(StepType.MARKER, "mark", Map.of("message", "M")),
                step(StepType.FLAKY, "flaky", Map.of("failFirst", "1")));
        return p;
    }

    private RemediationPolicy markerPolicy() {
        RemediationPolicy p = new RemediationPolicy();
        p.id = "p-marker";
        p.matchAnyKeywords = List.of("mem");
        p.maxRetries = 0;
        p.stepTimeoutMillis = 1000;
        p.steps = List.of(step(StepType.MARKER, "mark", Map.of("message", "Q")));
        return p;
    }

    @Test
    void shutdownDuringBackoffAndQueueThenRestartResumesToSuccess() throws Exception {
        var policies = List.of(retryPolicy(), markerPolicy());

        // === 进程 1：真实调度跑起来；A 在退避等待重试，B 在排队 ===
        Stack s1 = newStack(policies, 1); // 并发上限 1：A 占槽退避期间 B 只能排队
        s1.dispatcher.start();

        String taskA = s1.engine.accept(new Alert("a1", "host-SD1", "disk full", "CRITICAL", 1)).taskId();
        String taskB = s1.engine.accept(new Alert("b1", "host-SD2", "mem pressure", "WARN", 1)).taskId();
        log.info("输入: taskA={} (MARKER+FLAKY failFirst=1, maxRetries=2, backoff=500ms), taskB={} (单 MARKER)",
                taskA, taskB);

        await(5000, () -> s1.store.findById(taskA).orElseThrow().state == TaskState.RETRY_WAIT);
        await(5000, () -> s1.store.findById(taskB).orElseThrow().state == TaskState.QUEUED);
        RemediationTask aWait = s1.store.findById(taskA).orElseThrow();
        log.info("关停前现场: A.state={} A.step1.attempts={} A.step1.nextAttempt={} A.nextAttemptAt={} B.state={}",
                aWait.state, aWait.steps.get(1).attempts, aWait.steps.get(1).nextAttempt,
                aWait.nextAttemptAt, s1.store.findById(taskB).orElseThrow().state);
        assertEquals(1, aWait.steps.get(1).attempts, "FLAKY 已失败 1 次");
        assertEquals(StepState.SUCCEEDED, aWait.steps.get(0).state, "step0 已成功");

        // 判定依据：等待重试的修复是“未结束”的，等价告警必须按在途重复合并，而不是走复发路径
        AcceptResult dup = s1.engine.accept(new Alert("a1-dup", "host-SD1", "disk full", "CRITICAL", 2));
        log.info("退避期间等价告警: {} reason='{}'", dup.disposition(), dup.reason());
        assertEquals(DispositionCode.DUPLICATE, dup.disposition());
        assertEquals(taskA, dup.taskId());

        // === 优雅关停（非强杀）：stop() 返回时落库状态已确定 ===
        s1.dispatcher.stop();

        RemediationTask aAfterStop = s1.store.findById(taskA).orElseThrow();
        RemediationTask bAfterStop = s1.store.findById(taskB).orElseThrow();
        log.info("关停后落库: A.state={} B.state={} (都必须是非终态，且没有人点过取消)",
                aAfterStop.state, bAfterStop.state);
        assertEquals(TaskState.RETRY_WAIT, aAfterStop.state, "退避等待被关停打断不得写成终态");
        assertEquals(TaskState.QUEUED, bAfterStop.state, "排队中的任务不得被关停触碰");
        assertFalse(aAfterStop.state.isTerminal());
        assertFalse(aAfterStop.cancelRequested);
        assertTrue(s1.audit.findByTaskId(taskA).stream()
                .noneMatch(e -> AuditEvents.TASK_CANCELLED.equals(e.event())), "关停不得产生取消审计");
        assertTrue(s1.audit.findByTaskId(taskA).stream()
                .anyMatch(e -> AuditEvents.TASK_SHUTDOWN_INTERRUPTED.equals(e.event())), "关停打断必须有可区分审计");

        // === 进程 2：同一数据目录重启，恢复扫描后按原安排继续推进 ===
        Stack s2 = newStack(policies, 1);
        s2.dispatcher.start(); // start() 内部先做恢复扫描

        await(5000, () -> s2.store.findById(taskA).orElseThrow().state == TaskState.SUCCEEDED);
        await(5000, () -> s2.store.findById(taskB).orElseThrow().state == TaskState.SUCCEEDED);

        RemediationTask aFinal = s2.store.findById(taskA).orElseThrow();
        log.info("恢复后终态: A.state={} steps={}", aFinal.state,
                aFinal.steps.stream().map(s -> s.index + ":" + s.state + "(attempts=" + s.attempts + ")").toList());
        assertEquals(TaskState.SUCCEEDED, aFinal.state);
        assertEquals(1, aFinal.steps.get(0).attempts, "已成功步骤不得重跑");
        assertEquals(2, aFinal.steps.get(1).attempts,
                "重试进度跨重启延续：关停前 1 次 + 恢复后补 1 次，不清零也不多跑");

        // 副作用断言：step0 的 MARKER 只写一次；FLAKY 计数器文件恰为 2
        List<String> markerLines = Files.readAllLines(tempDir.resolve("markers/host-SD1.log"));
        long step0Lines = markerLines.stream().filter(l -> l.contains("step=0")).count();
        log.info("副作用统计: A step0 marker 行数={}（必须为1）, FLAKY 计数={}",
                step0Lines, Files.readString(tempDir.resolve("flaky/" + taskA + "-1.cnt")));
        assertEquals(1, step0Lines, "已成功步骤的副作用不得因重启重复");
        assertEquals("2", Files.readString(tempDir.resolve("flaky/" + taskA + "-1.cnt")).trim());

        RemediationTask bFinal = s2.store.findById(taskB).orElseThrow();
        log.info("恢复后终态: B.state={} (排队中的任务重启后继续排队并最终完成)", bFinal.state);
        assertEquals(TaskState.SUCCEEDED, bFinal.state);
        assertEquals(1, Files.readAllLines(tempDir.resolve("markers/host-SD2.log")).size());

        // 审计链完整：恢复扫描 + 恢复续跑 + 成功，全程无取消
        assertTrue(s2.audit.findAll().stream().anyMatch(e -> AuditEvents.RECOVERY_SCAN.equals(e.event())));
        assertTrue(s2.audit.findByTaskId(taskA).stream()
                .anyMatch(e -> AuditEvents.RECOVERY_RESUMED.equals(e.event())));
        assertTrue(s2.audit.findAll().stream()
                .noneMatch(e -> AuditEvents.TASK_CANCELLED.equals(e.event())), "全程无人取消，不得出现取消审计");
        s2.dispatcher.stop();
    }

    @Test
    void cancelRacingShutdownResolvesToSingleCancelledOutcome() throws Exception {
        // FLAKY 一直失败，保证任务停在退避等待里
        RemediationPolicy p = retryPolicy();
        p.steps = List.of(step(StepType.FLAKY, "flaky", Map.of("failFirst", "99")));
        var policies = List.of(p);

        Stack s1 = newStack(policies, 1);
        s1.dispatcher.start();
        String taskId = s1.engine.accept(new Alert("c1", "host-SD9", "disk full", "CRITICAL", 1)).taskId();
        await(5000, () -> s1.store.findById(taskId).orElseThrow().state == TaskState.RETRY_WAIT);
        log.info("输入: task={} 退避等待中，几乎同时发起主动取消 + 优雅关停", taskId);

        // 取消与关停几乎同时：判定依据必须是持久化的 cancelRequested（取消优先）
        assertTrue(s1.engine.cancel(taskId, "operator abort during backoff"));
        s1.dispatcher.stop();

        RemediationTask afterStop = s1.store.findById(taskId).orElseThrow();
        log.info("关停后落库: state={} reason='{}'", afterStop.state, afterStop.terminalReason);
        assertEquals(TaskState.CANCELLED, afterStop.state, "主动取消必须正常收尾为 CANCELLED");
        assertTrue(s1.audit.findByTaskId(taskId).stream()
                .anyMatch(e -> AuditEvents.TASK_CANCELLED.equals(e.event())));

        // 重启恢复：终态不被复活，不会出现“先记已结束、重启后又接着执行”
        Stack s2 = newStack(policies, 1);
        s2.dispatcher.start();
        Thread.sleep(300); // 给恢复扫描与调度留出时间
        RemediationTask afterRestart = s2.store.findById(taskId).orElseThrow();
        log.info("重启后: state={} (终态必须保持，不得复活)", afterRestart.state);
        assertEquals(TaskState.CANCELLED, afterRestart.state);
        assertTrue(s2.audit.findByTaskId(taskId).stream()
                .noneMatch(e -> AuditEvents.RECOVERY_RESUMED.equals(e.event())), "终态任务不得被恢复重排");
        s2.dispatcher.stop();
    }
}
