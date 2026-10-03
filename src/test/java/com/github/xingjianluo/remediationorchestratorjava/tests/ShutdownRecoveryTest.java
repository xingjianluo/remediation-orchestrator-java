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

import static com.github.xingjianluo.remediationorchestratorjava.tests.TestFixtures.await;
import static com.github.xingjianluo.remediationorchestratorjava.tests.TestFixtures.step;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 优雅关停恰好落在“退避等待重试 / 排队等执行”期间时的恢复语义：
 * <ul>
 *   <li>关停不得把未做完的修复写成 CANCELLED 等终态；</li>
 *   <li>重启后按原安排继续推进（该重试的重试、该排队的排队），已成功的步骤不重跑、
 *       已累计的尝试次数不清零；</li>
 *   <li>关停前已受理的主动取消，由恢复器收尾为 CANCELLED，与关停打断严格区分。</li>
 * </ul>
 * 全部使用真实文件存储 + 真实调度线程，重启 = 在同一数据目录上重新初始化一整套实例。
 */
class ShutdownRecoveryTest {

    private static final Logger log = LoggerFactory.getLogger(ShutdownRecoveryTest.class);

    @TempDir
    Path tempDir;

    /** 一套“进程实例”：同一数据目录可反复 new 出来模拟重启。 */
    record Stack(FileTaskStore store, FileAuditLog audit, OrchestratorProperties props, ObjectMapper mapper) {
    }

    private Stack newStack() {
        OrchestratorProperties props = new OrchestratorProperties();
        props.setMaxConcurrent(1);
        props.setTickMillis(20);
        props.setDefaultMaxRetries(1);
        props.setDefaultBackoffBaseMillis(30_000); // 长退避：关停必然落在等待重试期间
        props.setDefaultStepTimeoutMillis(1000);
        props.setDefaultApprovalTimeoutSeconds(60);
        ObjectMapper mapper = new ObjectMapper();
        FileTaskStore store = new FileTaskStore(tempDir.resolve("tasks"), mapper);
        store.init();
        FileAuditLog audit = new FileAuditLog(tempDir.resolve("audit.jsonl"), mapper);
        audit.init();
        return new Stack(store, audit, props, mapper);
    }

    private TaskDispatcher newDispatcher(Stack s, List<RemediationPolicy> policies) {
        Clock clock = Clock.systemUTC();
        MaintenanceWindowService mw = new MaintenanceWindowService();
        ApprovalService approvals = new ApprovalService(s.store, s.audit, s.props, clock);
        RecurrenceService recurrence = new RecurrenceService(
                new InMemoryRecurrenceStore(), s.audit, s.props, clock);
        TaskEngine engine = new TaskEngine(s.store, s.audit, new PolicyMatcher(policies),
                mw, s.props, clock, recurrence);
        engine.setApprovalService(approvals);
        StepExecutor executor = new StepExecutor(LocalStepHandlers.defaults(tempDir));
        RecoveryService recovery = new RecoveryService(s.store, s.audit, clock);
        return new TaskDispatcher(s.store, s.audit, executor, approvals, recovery, s.props, clock);
    }

    private TaskEngine newEngine(Stack s, List<RemediationPolicy> policies) {
        Clock clock = Clock.systemUTC();
        ApprovalService approvals = new ApprovalService(s.store, s.audit, s.props, clock);
        RecurrenceService recurrence = new RecurrenceService(
                new InMemoryRecurrenceStore(), s.audit, s.props, clock);
        TaskEngine engine = new TaskEngine(s.store, s.audit, new PolicyMatcher(policies),
                new MaintenanceWindowService(), s.props, clock, recurrence);
        engine.setApprovalService(approvals);
        return engine;
    }

    /** step0 MARKER（必成功，留下副作用证据）+ step1 FLAKY（第 1 次失败、第 2 次成功）。 */
    private RemediationPolicy flakyAfterMarkerPolicy() {
        RemediationPolicy policy = new RemediationPolicy();
        policy.id = "p-flaky-after-marker";
        policy.matchAnyKeywords = List.of("flaky");
        policy.maxRetries = 1;
        policy.backoffBaseMillis = 30_000;
        policy.stepTimeoutMillis = 1000;
        policy.steps = List.of(
                step(StepType.MARKER, "mark-first", Map.of("message", "ONE")),
                step(StepType.FLAKY, "flaky-second", Map.of("failFirst", "1")));
        return policy;
    }

    @Test
    void shutdownDuringBackoffKeepsTaskUnfinishedAndResumesAfterRestart() throws Exception {
        var policies = List.of(flakyAfterMarkerPolicy());

        // === 第一次启动：真实调度跑起来，step0 成功、step1 第一次失败进入退避等待 ===
        Stack s1 = newStack();
        TaskDispatcher d1 = newDispatcher(s1, policies);
        d1.start();
        String taskId = newEngine(s1, policies)
                .accept(new Alert("a-shutdown", "host-S", "flaky disk", "WARN", 1)).taskId();
        log.info("输入: alert=a-shutdown target=host-S 策略=maxRetries=1 backoff=30s, "
                + "step0=MARKER(必成功), step1=FLAKY(第1次失败)");
        await(5000, () -> s1.store.findById(taskId).orElseThrow().state == TaskState.RETRY_WAIT);

        RemediationTask waiting = s1.store.findById(taskId).orElseThrow();
        log.info("关停前关键状态: state={} nextAttemptAt={} steps={}",
                waiting.state, waiting.nextAttemptAt,
                waiting.steps.stream().map(x -> x.index + ":" + x.state + "(attempts=" + x.attempts + ")").toList());
        assertEquals(TaskState.RETRY_WAIT, waiting.state);
        assertEquals(StepState.SUCCEEDED, waiting.steps.get(0).state);
        assertEquals(1, waiting.steps.get(0).attempts);
        assertEquals(1, waiting.steps.get(1).attempts, "已失败一次，累计尝试数必须落库");
        assertTrue(waiting.nextAttemptAt > 0);

        // === 退避等待期间优雅关停（非强杀）===
        d1.stop();
        Thread.sleep(300); // 等工作线程收尾动作（若有）落定

        RemediationTask afterStop = s1.store.findById(taskId).orElseThrow();
        log.info("关停后判定依据: state={} terminal={} cancelRequested={}",
                afterStop.state, afterStop.state.isTerminal(), afterStop.cancelRequested);
        assertEquals(TaskState.RETRY_WAIT, afterStop.state,
                "关停不得把等待重试中的任务写成终态（历史上会被错记为 CANCELLED）");
        assertFalse(afterStop.state.isTerminal());
        assertTrue(s1.audit.findByTaskId(taskId).stream()
                        .noneMatch(e -> AuditEvents.TASK_CANCELLED.equals(e.event())),
                "没人点取消，审计里不得出现 TASK_CANCELLED");

        // === 模拟重启：同一数据目录上重新初始化一整套实例 ===
        Stack s2 = newStack();
        RemediationTask reloaded = s2.store.findById(taskId).orElseThrow();
        assertEquals(TaskState.RETRY_WAIT, reloaded.state, "重启后读到的应是关停时的 RETRY_WAIT");
        assertEquals(1, reloaded.steps.get(1).attempts, "重启后累计尝试数不得清零");
        TaskDispatcher d2 = newDispatcher(s2, policies);
        d2.start(); // start() 内部先做恢复扫描

        await(5000, () -> s2.store.findById(taskId).orElseThrow().state == TaskState.SUCCEEDED);
        RemediationTask finished = s2.store.findById(taskId).orElseThrow();
        log.info("恢复后关键迁移: state={} steps={}",
                finished.state,
                finished.steps.stream().map(x -> x.index + ":" + x.state + "(attempts=" + x.attempts + ")").toList());
        assertEquals(TaskState.SUCCEEDED, finished.state);
        assertEquals(1, finished.steps.get(0).attempts, "已成功步骤不得因重启重跑");
        assertEquals(2, finished.steps.get(1).attempts, "重试次数要累计（关停前 1 次 + 恢复后 1 次），不得清零");

        // 副作用幂等：step0(MARKER) 在 marker 文件中只能出现一次；
        // step1(FLAKY) 的副作用是计数文件，必须恰好是 2（关停前失败 1 次 + 恢复后成功 1 次）
        List<String> lines = Files.readAllLines(tempDir.resolve("markers/host-S.log"));
        long step0Lines = lines.stream().filter(l -> l.contains("step=0")).count();
        String flakyCount = Files.readString(
                tempDir.resolve("flaky/" + taskId + "-1.cnt")).trim();
        log.info("副作用统计: step0 marker 行数={}（必须为1）, step1 flaky 计数={}（必须为2）",
                step0Lines, flakyCount);
        assertEquals(1, step0Lines, "已成功步骤的副作用不得因重启重复");
        assertEquals("2", flakyCount, "FLAKY 步骤真实执行次数 = 关停前 1 次 + 恢复后 1 次");

        // 恢复审计：从 RETRY_WAIT 重新排队；全程无 TASK_CANCELLED
        assertTrue(s2.audit.findByTaskId(taskId).stream().anyMatch(e ->
                AuditEvents.RECOVERY_RESUMED.equals(e.event())
                        && "RETRY_WAIT".equals(String.valueOf(e.detail().get("from")))));
        assertTrue(s2.audit.findByTaskId(taskId).stream()
                .noneMatch(e -> AuditEvents.TASK_CANCELLED.equals(e.event())));
        d2.stop();
    }

    @Test
    void queuedTaskSurvivesShutdownAndRunsAfterRestart() throws Exception {
        // 槽位 1：任务1 SLOW 占槽，任务2 MARKER 排队期间关停
        RemediationPolicy slow = new RemediationPolicy();
        slow.id = "p-slow";
        slow.matchAnyKeywords = List.of("slow");
        slow.maxRetries = 0;
        slow.stepTimeoutMillis = 60_000;
        slow.steps = List.of(step(StepType.SLOW, "slow-step", Map.of("sleepMillis", "1500")));
        RemediationPolicy marker = new RemediationPolicy();
        marker.id = "p-mark";
        marker.matchAnyKeywords = List.of("mark");
        marker.maxRetries = 0;
        marker.steps = List.of(step(StepType.MARKER, "mark-step", Map.of("message", "M")));
        var policies = List.of(slow, marker);

        Stack s1 = newStack();
        TaskDispatcher d1 = newDispatcher(s1, policies);
        d1.start();
        TaskEngine engine = newEngine(s1, policies);
        String first = engine.accept(new Alert("a-occupy", "host-Q1", "slow one", "WARN", 1)).taskId();
        String queued = engine.accept(new Alert("a-queued", "host-Q2", "mark it", "WARN", 2)).taskId();
        await(5000, () -> s1.store.findById(first).orElseThrow().state == TaskState.RUNNING);
        await(5000, () -> s1.store.findById(queued).orElseThrow().state == TaskState.QUEUED);
        log.info("关停前: 任务1={} RUNNING(占槽), 任务2={} QUEUED(排队)", first, queued);

        d1.stop(); // 排队期间 + 执行期间同时遭遇优雅关停
        Thread.sleep(300);

        RemediationTask q = s1.store.findById(queued).orElseThrow();
        RemediationTask r = s1.store.findById(first).orElseThrow();
        log.info("关停后: 排队任务 state={}, 占槽任务 state={}", q.state, r.state);
        assertEquals(TaskState.QUEUED, q.state, "排队中的任务不得被关停写成终态");
        assertFalse(r.state.isTerminal(), "执行中的任务不得被关停写成终态");

        Stack s2 = newStack();
        TaskDispatcher d2 = newDispatcher(s2, policies);
        d2.start();
        await(8000, () -> s2.store.findById(queued).orElseThrow().state == TaskState.SUCCEEDED);
        await(8000, () -> s2.store.findById(first).orElseThrow().state == TaskState.SUCCEEDED);
        RemediationTask qDone = s2.store.findById(queued).orElseThrow();
        log.info("恢复后: 排队任务 state={} startedAt={}", qDone.state, qDone.startedAt);
        assertEquals(TaskState.SUCCEEDED, qDone.state);
        assertTrue(qDone.startedAt > 0, "排队任务重启后必须真正被调度执行");
        assertEquals(1, Files.readAllLines(tempDir.resolve("markers/host-Q2.log")).size(),
                "排队任务的副作用只能产生一次");
        d2.stop();
    }

    @Test
    void pendingCancelAtShutdownIsFinalizedOnRecovery() {
        // 关停与主动取消几乎同时发生：取消标志已落库（RUNNING + cancelRequested），
        // 但工作线程没来得及收尾进程就停了。恢复器必须把取消收尾为 CANCELLED，
        // 且终态之后不再被调度——结论唯一：取消优先。
        Stack s1 = newStack();
        RemediationTask task = new RemediationTask();
        task.id = "task-cancel-pending";
        task.fingerprint = "fp-cancel-pending";
        task.policyId = "p-x";
        task.target = "host-X";
        task.alert = new Alert("a-x", "host-X", "disk full", "CRITICAL", 1);
        task.state = TaskState.RUNNING;
        task.cancelRequested = true;
        task.createdAt = 100;
        task.updatedAt = 200;
        task.queuedAt = 100;
        task.startedAt = 150;
        task.maxRetries = 0;
        TaskStep s0 = new TaskStep();
        s0.index = 0;
        s0.type = StepType.MARKER;
        s0.name = "mark";
        s0.params = Map.of();
        s0.state = StepState.RUNNING;
        s0.attempts = 1;
        task.steps = new java.util.ArrayList<>(List.of(s0));
        s1.store.save(task);
        log.info("构造现场: RUNNING + cancelRequested=true（取消与关停同时发生，工作线程未及收尾）");

        Stack s2 = newStack();
        RecoveryService recovery = new RecoveryService(s2.store, s2.audit, Clock.systemUTC());
        int resumed = recovery.recoverOnStartup();
        RemediationTask finalized = s2.store.findById(task.id).orElseThrow();
        log.info("恢复判定: resumed={} state={} reason='{}'", resumed, finalized.state, finalized.terminalReason);
        assertEquals(0, resumed, "带取消标志的任务不得被恢复重排");
        assertEquals(TaskState.CANCELLED, finalized.state, "取消优先：必须收尾为 CANCELLED 而非继续执行");
        assertEquals(StepState.CANCELLED, finalized.steps.get(0).state);
        assertTrue(finalized.finishedAt > 0);
        assertTrue(s2.audit.findByTaskId(task.id).stream().anyMatch(e ->
                AuditEvents.TASK_CANCELLED.equals(e.event())
                        && "recovery".equals(String.valueOf(e.detail().get("stage")))),
                "审计必须能区分：这是恢复器收尾的取消，而非执行期取消");

        // 再跑一次恢复：终态任务一律不触碰，不会被反复收尾或复活
        recovery.recoverOnStartup();
        assertEquals(TaskState.CANCELLED, s2.store.findById(task.id).orElseThrow().state);
    }
}
