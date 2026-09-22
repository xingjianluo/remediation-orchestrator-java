package com.github.xingjianluo.remediationorchestratorjava.tests;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.xingjianluo.remediationorchestratorjava.config.OrchestratorProperties;
import com.github.xingjianluo.remediationorchestratorjava.engine.PolicyMatcher;
import com.github.xingjianluo.remediationorchestratorjava.engine.RecoveryService;
import com.github.xingjianluo.remediationorchestratorjava.engine.TaskDispatcher;
import com.github.xingjianluo.remediationorchestratorjava.engine.TaskEngine;
import com.github.xingjianluo.remediationorchestratorjava.approval.ApprovalService;
import com.github.xingjianluo.remediationorchestratorjava.executor.LocalStepHandlers;
import com.github.xingjianluo.remediationorchestratorjava.executor.StepExecutor;
import com.github.xingjianluo.remediationorchestratorjava.maintenance.MaintenanceWindowService;
import com.github.xingjianluo.remediationorchestratorjava.model.*;
import com.github.xingjianluo.remediationorchestratorjava.store.AuditLog;
import com.github.xingjianluo.remediationorchestratorjava.store.FileAuditLog;
import com.github.xingjianluo.remediationorchestratorjava.store.FileTaskStore;
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
 * 进程重启恢复：使用真实文件存储，构造一个“步骤1已成功、步骤2执行中崩溃”的任务，
 * 新实例启动恢复后必须从步骤2继续，步骤1绝不重复执行。
 */
class RecoveryTest {

    private static final Logger log = LoggerFactory.getLogger(RecoveryTest.class);

    @TempDir
    Path tempDir;

    record Stack(FileTaskStore store, FileAuditLog audit, OrchestratorProperties props, ObjectMapper mapper) {
    }

    private Stack newStack() {
        OrchestratorProperties props = new OrchestratorProperties();
        props.setMaxConcurrent(2);
        props.setTickMillis(20);
        props.setDefaultMaxRetries(0);
        props.setDefaultBackoffBaseMillis(10);
        props.setDefaultStepTimeoutMillis(1000);
        props.setDefaultApprovalTimeoutSeconds(60);
        ObjectMapper mapper = new ObjectMapper();
        FileTaskStore store = new FileTaskStore(tempDir.resolve("tasks"), mapper);
        store.init();
        FileAuditLog audit = new FileAuditLog(tempDir.resolve("audit.jsonl"), mapper);
        audit.init();
        return new Stack(store, audit, props, mapper);
    }

    private TaskDispatcher newDispatcher(Stack s, List<RemediationPolicy> policies, Clock clock) {
        MaintenanceWindowService mw = new MaintenanceWindowService();
        ApprovalService approvals = new ApprovalService(s.store, s.audit, s.props, clock);
        TaskEngine engine = new TaskEngine(s.store, s.audit, new PolicyMatcher(policies),
                mw, s.props, clock);
        engine.setApprovalService(approvals);
        StepExecutor executor = new StepExecutor(LocalStepHandlers.defaults(tempDir));
        RecoveryService recovery = new RecoveryService(s.store, s.audit, clock);
        return new TaskDispatcher(s.store, s.audit, executor, approvals, recovery, s.props, clock);
    }

    private RemediationPolicy twoStepMarkerPolicy() {
        RemediationPolicy policy = new RemediationPolicy();
        policy.id = "p-marker";
        policy.matchAnyKeywords = List.of("disk");
        policy.maxRetries = 0;
        policy.stepTimeoutMillis = 1000;
        policy.steps = List.of(
                step(StepType.MARKER, "step-one", Map.of("message", "ONE")),
                step(StepType.MARKER, "step-two", Map.of("message", "TWO")));
        return policy;
    }

    private RemediationTask crashedTask() {
        // 直接构造崩溃现场：step0 已 SUCCEEDED，step1 停在 RUNNING，任务整体 RUNNING
        RemediationTask task = new RemediationTask();
        task.id = "task-crashed";
        task.fingerprint = "fp-crashed";
        task.policyId = "p-marker";
        task.target = "host-Z";
        task.alert = new Alert("a-z", "host-Z", "disk full", "CRITICAL", 1);
        task.state = TaskState.RUNNING;
        task.approval = ApprovalStatus.NONE;
        task.createdAt = 100;
        task.updatedAt = 200;
        task.queuedAt = 100;
        task.startedAt = 150;
        task.maxRetries = 0;
        task.stepTimeoutMillis = 1000;
        TaskStep s0 = new TaskStep();
        s0.index = 0;
        s0.type = StepType.MARKER;
        s0.name = "step-one";
        s0.params = Map.of("message", "ONE");
        s0.state = StepState.SUCCEEDED;
        s0.attempts = 1;
        TaskStep s1 = new TaskStep();
        s1.index = 1;
        s1.type = StepType.MARKER;
        s1.name = "step-two";
        s1.params = Map.of("message", "TWO");
        s1.state = StepState.RUNNING;
        s1.attempts = 1;
        task.steps = new java.util.ArrayList<>(List.of(s0, s1));
        return task;
    }

    @Test
    void resumesAfterRestartWithoutReRunningCompletedStep() throws Exception {
        Stack s1 = newStack();
        s1.store.save(crashedTask());
        Path markerFile = tempDir.resolve("markers/host-Z.log");
        Files.createDirectories(markerFile.getParent());
        Files.writeString(markerFile, "100 task=task-crashed target=host-Z step=0 msg=ONE\n");
        log.info("崩溃前现场已落盘: task=RUNNING, step0=SUCCEEDED, step1=RUNNING");

        // === 模拟进程重启：重新初始化存储与调度器 ===
        Stack s2 = newStack();
        assertEquals(TaskState.RUNNING, s2.store.findById("task-crashed").orElseThrow().state,
                "重启后直接读到的应是崩溃时的 RUNNING");
        Clock clock = Clock.systemUTC();
        var policies = List.of(twoStepMarkerPolicy());
        TaskDispatcher dispatcher = newDispatcher(s2, policies, clock);
        dispatcher.start(); // start() 内部先执行恢复扫描

        await(5000, () -> s2.store.findById("task-crashed").orElseThrow().state == TaskState.SUCCEEDED);
        RemediationTask recovered = s2.store.findById("task-crashed").orElseThrow();
        log.info("恢复后: state={} steps={}", recovered.state,
                recovered.steps.stream().map(x -> x.index + ":" + x.state + "(attempts=" + x.attempts + ")").toList());
        assertEquals(TaskState.SUCCEEDED, recovered.state);
        assertEquals(StepState.SUCCEEDED, recovered.steps.get(0).state);
        assertEquals(1, recovered.steps.get(0).attempts, "已完成步骤不得重复执行");
        assertEquals(StepState.SUCCEEDED, recovered.steps.get(1).state);

        // marker 文件中 step=0 只能出现一次（恢复未重跑步骤一），step=1 出现一次
        List<String> lines = Files.readAllLines(markerFile);
        long step0Lines = lines.stream().filter(l -> l.contains("step=0")).count();
        long step1Lines = lines.stream().filter(l -> l.contains("step=1")).count();
        log.info("marker 副作用统计: step0 行数={}（必须为1）, step1 行数={}", step0Lines, step1Lines);
        assertEquals(1, step0Lines, "已完成步骤的副作用不得重复");
        assertEquals(1, step1Lines);

        // 审计包含恢复扫描与恢复续跑事件
        assertTrue(s2.audit.findAll().stream().anyMatch(e -> AuditEvents.RECOVERY_SCAN.equals(e.event())));
        assertTrue(s2.audit.findByTaskId("task-crashed").stream()
                .anyMatch(e -> AuditEvents.RECOVERY_RESUMED.equals(e.event())));
        dispatcher.stop();
    }

    @Test
    void waitingApprovalPastDeadlineTimesOutOnRecovery() {
        Stack s1 = newStack();
        long now = System.currentTimeMillis();
        RemediationTask task = crashedTask();
        task.id = "task-await";
        task.fingerprint = "fp-await";
        task.state = TaskState.WAITING_APPROVAL;
        task.approval = ApprovalStatus.PENDING;
        task.approvalDeadline = now - 1000; // 停机期间已超时
        task.startedAt = 0;
        s1.store.save(task);

        Stack s2 = newStack();
        RecoveryService recovery = new RecoveryService(s2.store, s2.audit, Clock.systemUTC());
        int resumed = recovery.recoverOnStartup();
        RemediationTask reloaded = s2.store.findById("task-await").orElseThrow();
        log.info("恢复审批: resumed={} state={} approval={}", resumed, reloaded.state, reloaded.approval);
        assertEquals(0, resumed);
        assertEquals(TaskState.APPROVAL_TIMEOUT, reloaded.state);
        assertEquals(ApprovalStatus.TIMED_OUT, reloaded.approval);
    }
}
