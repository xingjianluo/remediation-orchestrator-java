package com.github.xingjianluo.remediationorchestratorjava.tests;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.xingjianluo.remediationorchestratorjava.approval.ApprovalService;
import com.github.xingjianluo.remediationorchestratorjava.config.OrchestratorProperties;
import com.github.xingjianluo.remediationorchestratorjava.engine.PolicyMatcher;
import com.github.xingjianluo.remediationorchestratorjava.engine.RecoveryService;
import com.github.xingjianluo.remediationorchestratorjava.engine.RecurrenceService;
import com.github.xingjianluo.remediationorchestratorjava.engine.TaskDispatcher;
import com.github.xingjianluo.remediationorchestratorjava.engine.TaskEngine;
import com.github.xingjianluo.remediationorchestratorjava.executor.LocalStepHandlers;
import com.github.xingjianluo.remediationorchestratorjava.executor.StepExecutor;
import com.github.xingjianluo.remediationorchestratorjava.maintenance.MaintenanceWindowService;
import com.github.xingjianluo.remediationorchestratorjava.model.*;
import com.github.xingjianluo.remediationorchestratorjava.store.EscalationStore;
import com.github.xingjianluo.remediationorchestratorjava.store.FileAuditLog;
import com.github.xingjianluo.remediationorchestratorjava.store.FileTaskStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static com.github.xingjianluo.remediationorchestratorjava.tests.TestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 复发信息跨重启连续：复发计数、抑制窗口、人工升级决定不清零；
 * 关停时处于 QUEUED / RETRY_WAIT 的修复不被误当终结，重启后按原安排推进。
 */
class RecurrenceRecoveryTest {

    private static final Logger log = LoggerFactory.getLogger(RecurrenceRecoveryTest.class);

    @TempDir
    Path tempDir;

    /** 一个“进程”全量组件：文件任务存储 + 文件升级决定存储，共享同一可推进时钟。 */
    record Stack(FileTaskStore store, FileAuditLog audit, EscalationStore escalations,
                 RecurrenceService recurrence, TaskEngine engine, TaskDispatcher dispatcher,
                 OrchestratorProperties props) {
    }

    private Stack newStack(TestFixtures.MutableClock clock, List<RemediationPolicy> policies) {
        OrchestratorProperties props = new OrchestratorProperties();
        props.setMaxConcurrent(2);
        props.setTickMillis(20);
        props.setDefaultMaxRetries(0);
        props.setDefaultBackoffBaseMillis(10);
        props.setDefaultStepTimeoutMillis(1000);
        props.setRecurrenceSuppressionWindowMillis(60_000);
        props.setRecurrenceStatsWindowMillis(3_600_000);
        props.setRecurrenceEscalationThreshold(1);
        ObjectMapper mapper = new ObjectMapper();
        FileTaskStore store = new FileTaskStore(tempDir.resolve("tasks"), mapper);
        store.init();
        FileAuditLog audit = new FileAuditLog(tempDir.resolve("audit.jsonl"), mapper);
        audit.init();
        EscalationStore escalations = new EscalationStore(tempDir.resolve("escalations.json"), mapper);
        escalations.init();
        RecurrenceService recurrence = new RecurrenceService(store, escalations, audit, props);
        ApprovalService approvals = new ApprovalService(store, audit, props, clock);
        TaskEngine engine = new TaskEngine(store, audit, new PolicyMatcher(policies),
                new MaintenanceWindowService(), props, clock, recurrence);
        engine.setApprovalService(approvals);
        StepExecutor executor = new StepExecutor(LocalStepHandlers.defaults(tempDir));
        RecoveryService recovery = new RecoveryService(store, audit, clock);
        TaskDispatcher dispatcher = new TaskDispatcher(store, audit, executor, approvals,
                recovery, props, clock);
        return new Stack(store, audit, escalations, recurrence, engine, dispatcher, props);
    }

    @Test
    void recurrenceStateAndUnfinishedTasksSurviveRestart() throws Exception {
        RemediationPolicy noop = policy("p-noop", List.of("disk full"), false,
                List.of(step(StepType.NOOP, "cleanup")));
        RemediationPolicy flaky = policy("p-flaky", List.of("flaky"), false,
                List.of(step(StepType.FLAKY, "flaky-fix", Map.of("failFirst", "1"))));
        flaky.maxRetries = 2;
        flaky.backoffBaseMillis = 2000;
        var policies = List.of(noop, flaky);
        var clock = new TestFixtures.MutableClock(1_000_000L);

        // ---- 进程 A ----
        Stack a = newStack(clock, policies);
        a.dispatcher().start();

        // 1) host-A 修复成功，留下终结历史（复发计数来源）
        AcceptResult r1 = a.engine().accept(new Alert("A-1", "host-A", "disk full", "WARN", 1));
        await(5000, () -> a.store().findById(r1.taskId())
                .map(t -> t.state == TaskState.SUCCEEDED).orElse(false));
        log.info("进程A: host-A 任务 {} SUCCEEDED", r1.taskId());

        // 2) host-C 修复进入重试等待后关停（退避 2s 足够覆盖关停窗口）
        AcceptResult r3 = a.engine().accept(new Alert("C-1", "host-C", "flaky thing", "WARN", 1));
        await(5000, () -> a.store().findById(r3.taskId())
                .map(t -> t.state == TaskState.RETRY_WAIT).orElse(false));
        log.info("进程A: host-C 任务 {} 进入 RETRY_WAIT，准备关停", r3.taskId());
        a.dispatcher().stop();
        RemediationTask retryTask = a.store().findById(r3.taskId()).orElseThrow();
        assertEquals(TaskState.RETRY_WAIT, retryTask.state,
                "关停发生在等待重试期间：任务必须保持 RETRY_WAIT，不得被写成终态");
        assertFalse(retryTask.state.isTerminal());

        // 3) 关停后受理 host-B 告警：保持 QUEUED（模拟排队中崩溃）
        AcceptResult r2 = a.engine().accept(new Alert("B-1", "host-B", "disk full", "WARN", 2));
        assertEquals(DispositionCode.CREATED, r2.disposition());

        // 4) 人工放弃 host-A 的复发（阈值 1，已升级）
        String fpA = TaskEngine.fingerprint(noop, new Alert("x", "host-A", "disk full", "WARN", 0));
        a.recurrence().abandon(fpA, "oncall", "known noise", clock.millis());
        log.info("进程A: 已记录 abandon 决定 fingerprint={}", fpA);

        // ---- 进程 B（同目录重启） ----
        Stack b = newStack(clock, policies);

        // 复发计数与人工决定跨重启连续
        var statusA = b.recurrence().statusForTarget("host-A", clock.millis());
        assertEquals(1, statusA.size());
        log.info("进程B: host-A 复发计数={} 决定={}", statusA.get(0).recurrenceCount(),
                statusA.get(0).decision());
        assertEquals(1, statusA.get(0).recurrenceCount(), "重启后复发计数不得清零");
        assertEquals(EscalationDecision.Kind.ABANDONED, statusA.get(0).decision(),
                "人工放弃决定必须跨重启保持");

        // 等价告警再来：按重启前累计状态判定为“已放弃”，而非全新修复
        AcceptResult afterRestart = b.engine().accept(new Alert("A-2", "host-A", "disk full", "WARN", 3));
        log.info("进程B: host-A 复发判定 {} reason='{}'", afterRestart.disposition(), afterRestart.reason());
        assertEquals(DispositionCode.RECURRENCE_ABANDONED, afterRestart.disposition());

        // 排队中的 host-B 未被误当终结：等价告警按 DUPLICATE 合并
        AcceptResult dupB = b.engine().accept(new Alert("B-2", "host-B", "disk full", "WARN", 4));
        log.info("进程B: host-B 重复告警判定 {} -> {}", dupB.disposition(), dupB.taskId());
        assertEquals(DispositionCode.DUPLICATE, dupB.disposition());
        assertEquals(r2.taskId(), dupB.taskId());

        // 恢复前：host-C 仍是 RETRY_WAIT（非终态），host-B 仍 QUEUED
        assertEquals(TaskState.RETRY_WAIT, b.store().findById(r3.taskId()).orElseThrow().state);
        assertEquals(TaskState.QUEUED, b.store().findById(r2.taskId()).orElseThrow().state);

        // 重启调度：未完成的修复按原安排继续推进
        b.dispatcher().start();
        await(5000, () -> b.store().findById(r2.taskId())
                .map(t -> t.state == TaskState.SUCCEEDED).orElse(false));
        await(5000, () -> b.store().findById(r3.taskId())
                .map(t -> t.state == TaskState.SUCCEEDED).orElse(false));
        b.dispatcher().stop();
        RemediationTask recovered = b.store().findById(r3.taskId()).orElseThrow();
        log.info("进程B: host-C 任务 {} 恢复后推进到 {}, attempts 步骤状态={}",
                recovered.id, recovered.state,
                recovered.steps.stream().map(s -> s.index + ":" + s.state).toList());
        assertEquals(TaskState.SUCCEEDED, recovered.state,
                "等待重试期间关停的修复必须在重启后继续推进直至终结");

        // host-C 终结后，其复发历史立即可查（计数从持久化任务推导）
        var statusC = b.recurrence().statusForTarget("host-C", clock.millis());
        assertEquals(1, statusC.get(0).recurrenceCount());
    }
}
