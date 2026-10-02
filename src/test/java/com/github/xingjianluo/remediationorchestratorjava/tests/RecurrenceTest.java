package com.github.xingjianluo.remediationorchestratorjava.tests;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.xingjianluo.remediationorchestratorjava.config.OrchestratorProperties;
import com.github.xingjianluo.remediationorchestratorjava.engine.PolicyMatcher;
import com.github.xingjianluo.remediationorchestratorjava.engine.RecoveryService;
import com.github.xingjianluo.remediationorchestratorjava.engine.TaskEngine;
import com.github.xingjianluo.remediationorchestratorjava.maintenance.MaintenanceWindowService;
import com.github.xingjianluo.remediationorchestratorjava.model.*;
import com.github.xingjianluo.remediationorchestratorjava.recurrence.RecurrenceService;
import com.github.xingjianluo.remediationorchestratorjava.recurrence.RecurrenceStatus;
import com.github.xingjianluo.remediationorchestratorjava.store.FileAuditLog;
import com.github.xingjianluo.remediationorchestratorjava.store.FileRecurrenceStore;
import com.github.xingjianluo.remediationorchestratorjava.store.FileTaskStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;

import static com.github.xingjianluo.remediationorchestratorjava.tests.TestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 复发场景：修复终结后的等价告警按“抑制窗口 / 升级阈值 / 人工确认与放弃”分别处置，
 * 且复发计数与未完成修复跨重启保持连续。
 */
class RecurrenceTest {

    private static final Logger log = LoggerFactory.getLogger(RecurrenceTest.class);

    @TempDir
    Path tempDir;

    private static List<RemediationPolicy> noopPolicies() {
        return List.of(policy("p-disk", List.of("disk full"), false,
                List.of(step(StepType.NOOP, "cleanup"))));
    }

    private static Alert alert(String id, String target) {
        return new Alert(id, target, "disk full now", "CRITICAL", 0);
    }

    private static void markTerminal(TestFixtures.Env env, String taskId, TaskState state) {
        RemediationTask t = env.store.findById(taskId).orElseThrow();
        t.state = state;
        t.finishedAt = env.clock.millis();
        t.updatedAt = env.clock.millis();
        env.store.save(t);
        log.info("标记终态: task={} state={} finishedAt={}", taskId, state, t.finishedAt);
    }

    // ---------- 1. 修复结束后的复发：抑制窗口内合并抑制，窗口过后受控重开 ----------

    @Test
    void recurrenceSuppressedWithinWindowThenReopenedOnceAfterWindow() throws Exception {
        var env = newEnv(noopPolicies(), tempDir);
        env.startDispatcher();
        try {
            AcceptResult r1 = env.engine.accept(alert("a1", "host-A"));
            assertEquals(DispositionCode.CREATED, r1.disposition());
            String task1 = r1.taskId();
            await(5000, () -> env.store.findById(task1).orElseThrow().state == TaskState.SUCCEEDED);
            log.info("首次修复完成: task={} SUCCEEDED", task1);

            // 抑制窗口内复发：不新增修复动作
            AcceptResult r2 = env.engine.accept(alert("a2", "host-A"));
            log.info("窗口内复发: {} reason='{}'", r2.disposition(), r2.reason());
            assertEquals(DispositionCode.RECURRENCE_SUPPRESSED, r2.disposition());
            assertEquals(task1, r2.taskId());
            assertEquals(1, env.store.findByTarget("host-A").size(), "抑制窗口内不得新增任务");

            // 窗口过后：最多放行一次新修复
            env.clock.advance(env.props.getRecurrenceSuppressionWindowMillis() + 1);
            AcceptResult r3 = env.engine.accept(alert("a3", "host-A"));
            log.info("窗口过后复发: {} reason='{}'", r3.disposition(), r3.reason());
            assertEquals(DispositionCode.CREATED, r3.disposition());
            assertNotEquals(task1, r3.taskId());

            // 重开的修复仍在进行中：等价告警被合并而不是再次新建
            AcceptResult r4 = env.engine.accept(alert("a4", "host-A"));
            log.info("重开修复进行中复发: {} reason='{}'", r4.disposition(), r4.reason());
            assertEquals(DispositionCode.DUPLICATE, r4.disposition());
            assertEquals(r3.taskId(), r4.taskId());
            assertEquals(2, env.store.findByTarget("host-A").size(), "重开后不得再新建任务");

            // 重开的任务仍能正常执行完成（遵守既有调度约束）
            await(5000, () -> env.store.findById(r3.taskId()).orElseThrow().state == TaskState.SUCCEEDED);
            log.info("重开修复完成: task={} SUCCEEDED", r3.taskId());
        } finally {
            env.stopDispatcher();
        }
    }

    // ---------- 2. 抑制窗口边界 ----------

    @Test
    void suppressionWindowBoundaryIsExclusive() {
        var env = newEnv(noopPolicies(), tempDir);
        long window = env.props.getRecurrenceSuppressionWindowMillis();
        AcceptResult r1 = env.engine.accept(alert("b1", "host-B"));
        markTerminal(env, r1.taskId(), TaskState.SUCCEEDED);
        long finishedAt = env.clock.millis();

        env.clock.set(finishedAt + window - 1);
        AcceptResult inside = env.engine.accept(alert("b2", "host-B"));
        log.info("elapsed={}ms (< {}ms): {}", window - 1, window, inside.disposition());
        assertEquals(DispositionCode.RECURRENCE_SUPPRESSED, inside.disposition());

        env.clock.set(finishedAt + window);
        AcceptResult atBoundary = env.engine.accept(alert("b3", "host-B"));
        log.info("elapsed={}ms (== {}ms): {}", window, window, atBoundary.disposition());
        assertEquals(DispositionCode.CREATED, atBoundary.disposition(), "窗口届满即放行一次新修复");
    }

    // ---------- 3. 前次修复失败后复发：需人工确认，确认前无任何副作用 ----------

    @Test
    void recurrenceAfterFailureRequiresManualConfirmation() {
        var env = newEnv(noopPolicies(), tempDir);
        AcceptResult r1 = env.engine.accept(alert("c1", "host-C"));
        markTerminal(env, r1.taskId(), TaskState.FAILED);

        AcceptResult r2 = env.engine.accept(alert("c2", "host-C"));
        log.info("失败后复发: {} reason='{}'", r2.disposition(), r2.reason());
        assertEquals(DispositionCode.RECURRENCE_AFTER_FAILURE, r2.disposition());
        assertEquals(1, env.store.findByTarget("host-C").size(), "人工确认前不得新建任务（无副作用）");

        // 人工确认后下一次复发放行一次真实修复
        env.recurrence.confirm("host-C", "oncall");
        AcceptResult r3 = env.engine.accept(alert("c3", "host-C"));
        log.info("确认后复发: {} reason='{}'", r3.disposition(), r3.reason());
        assertEquals(DispositionCode.CREATED, r3.disposition());
        assertEquals(2, env.store.findByTarget("host-C").size());

        // 确认已被消费：新任务终结前的复发按在途重复合并
        AcceptResult r4 = env.engine.accept(alert("c4", "host-C"));
        assertEquals(DispositionCode.DUPLICATE, r4.disposition());
    }

    // ---------- 4. 达到阈值升级为人工确认；确认后放行；状态可查 ----------

    @Test
    void escalationAtThresholdBlocksUntilConfirmed() {
        var env = newEnv(noopPolicies(), tempDir);
        AcceptResult r1 = env.engine.accept(alert("d1", "host-D"));
        markTerminal(env, r1.taskId(), TaskState.SUCCEEDED);

        int threshold = env.props.getRecurrenceEscalationThreshold();
        for (int i = 1; i <= threshold + 1; i++) {
            env.clock.advance(100);
            AcceptResult r = env.engine.accept(alert("d-recur-" + i, "host-D"));
            log.info("第{}次复发: {} reason='{}'", i, r.disposition(), r.reason());
            if (i < threshold) {
                assertEquals(DispositionCode.RECURRENCE_SUPPRESSED, r.disposition());
            } else {
                assertEquals(DispositionCode.RECURRENCE_ESCALATED, r.disposition(),
                        "第" + i + "次复发（>=" + threshold + "）应升级为人工确认");
            }
        }
        assertEquals(1, env.store.findByTarget("host-D").size(), "升级后不得自动新建任务");

        RecurrenceStatus status = env.recurrence.status("host-D");
        log.info("升级状态: escalated={} count={} statsWindow={}ms suppressionWindow={}ms",
                status.escalated(), status.recurrenceCount(),
                status.statsWindowMillis(), status.suppressionWindowMillis());
        assertTrue(status.escalated());
        assertEquals(threshold + 1, status.recurrenceCount());
        assertEquals(env.props.getRecurrenceStatsWindowMillis(), status.statsWindowMillis());

        env.recurrence.confirm("host-D", "oncall");
        AcceptResult afterConfirm = env.engine.accept(alert("d-after-confirm", "host-D"));
        log.info("确认后复发: {} reason='{}'", afterConfirm.disposition(), afterConfirm.reason());
        assertEquals(DispositionCode.CREATED, afterConfirm.disposition());
        assertEquals(2, env.store.findByTarget("host-D").size());
    }

    // ---------- 5. 升级随统计窗滑动自然解除 ----------

    @Test
    void escalationLiftsNaturallyAsStatsWindowSlides() {
        var env = newEnv(noopPolicies(), tempDir);
        AcceptResult r1 = env.engine.accept(alert("e1", "host-E"));
        markTerminal(env, r1.taskId(), TaskState.SUCCEEDED);

        int threshold = env.props.getRecurrenceEscalationThreshold();
        for (int i = 1; i <= threshold; i++) {
            env.clock.advance(100);
            AcceptResult r = env.engine.accept(alert("e-recur-" + i, "host-E"));
            log.info("第{}次复发: {}", i, r.disposition());
        }
        assertTrue(env.recurrence.isEscalated("host-E"), "达到阈值应处于升级状态");

        // 不再推送新告警，统计窗滑过后升级自然解除
        env.clock.advance(env.props.getRecurrenceStatsWindowMillis() + 1);
        assertFalse(env.recurrence.isEscalated("host-E"), "窗口滑过后升级应自然解除");
        AcceptResult afterWindow = env.engine.accept(alert("e-after-window", "host-E"));
        log.info("窗口滑过后复发: {} reason='{}'", afterWindow.disposition(), afterWindow.reason());
        assertEquals(DispositionCode.CREATED, afterWindow.disposition(),
                "升级解除且抑制窗已过，应放行一次新修复（不永久卡死）");
    }

    // ---------- 6. 人工放弃：窗口内不再自动修复，窗口滑过后恢复常规判定 ----------

    @Test
    void abandonBlocksAutoRemediationUntilStatsWindowSlides() {
        var env = newEnv(noopPolicies(), tempDir);
        AcceptResult r1 = env.engine.accept(alert("f1", "host-F"));
        markTerminal(env, r1.taskId(), TaskState.FAILED);

        env.recurrence.abandon("host-F", "oncall", "known flaky, give up");
        AcceptResult r2 = env.engine.accept(alert("f2", "host-F"));
        log.info("放弃后复发: {} reason='{}'", r2.disposition(), r2.reason());
        assertEquals(DispositionCode.RECURRENCE_ABANDONED, r2.disposition());
        assertEquals(1, env.store.findByTarget("host-F").size(), "放弃后不得新建任务");

        env.clock.advance(env.props.getRecurrenceStatsWindowMillis() + 1);
        AcceptResult r3 = env.engine.accept(alert("f3", "host-F"));
        log.info("放弃窗口滑过后复发: {} reason='{}'", r3.disposition(), r3.reason());
        assertEquals(DispositionCode.RECURRENCE_AFTER_FAILURE, r3.disposition(),
                "放弃期结束后恢复常规判定（前次失败仍需人工确认）");
    }

    // ---------- 7. 重启后复发计数与未完成修复保持连续 ----------

    @Test
    void recurrenceStateAndUnfinishedTasksSurviveRestart() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var clock = new TestFixtures.MutableClock(10_000);
        OrchestratorProperties props = new OrchestratorProperties();
        props.setRecurrenceSuppressionWindowMillis(1_000);
        props.setRecurrenceStatsWindowMillis(5_000);
        props.setRecurrenceEscalationThreshold(5);
        var policies = List.of(
                policy("p-disk", List.of("disk full"), false, List.of(step(StepType.NOOP, "cleanup"))),
                policy("p-mem", List.of("mem leak"), false, List.of(step(StepType.NOOP, "gc"))));

        record Stack(FileTaskStore tasks, FileRecurrenceStore recurrence, FileAuditLog audit,
                     TaskEngine engine, RecurrenceService recurrenceService, RecoveryService recovery) {
        }
        java.util.function.Supplier<Stack> newStack = () -> {
            FileTaskStore tasks = new FileTaskStore(tempDir.resolve("tasks"), mapper);
            tasks.init();
            FileRecurrenceStore rec = new FileRecurrenceStore(tempDir.resolve("recurrence.json"), mapper);
            rec.init();
            FileAuditLog audit = new FileAuditLog(tempDir.resolve("audit.jsonl"), mapper);
            audit.init();
            RecurrenceService recService = new RecurrenceService(rec, audit, props, clock);
            TaskEngine engine = new TaskEngine(tasks, audit, new PolicyMatcher(policies),
                    new MaintenanceWindowService(), props, clock, recService);
            return new Stack(tasks, rec, audit, engine, recService,
                    new RecoveryService(tasks, audit, clock));
        };

        // === 进程 1：task-disk 成功终结并记录一次复发；task-mem 停在 RETRY_WAIT 时“关停” ===
        Stack s1 = newStack.get();
        AcceptResult a1 = s1.engine.accept(alert("r1", "host-R"));
        assertEquals(DispositionCode.CREATED, a1.disposition());
        RemediationTask t1 = s1.tasks.findById(a1.taskId()).orElseThrow();
        t1.state = TaskState.SUCCEEDED;
        t1.finishedAt = clock.millis();
        s1.tasks.save(t1);

        clock.advance(100);
        AcceptResult recur1 = s1.engine.accept(alert("r2", "host-R"));
        log.info("重启前复发: {} reason='{}'", recur1.disposition(), recur1.reason());
        assertEquals(DispositionCode.RECURRENCE_SUPPRESSED, recur1.disposition());
        assertEquals(1, s1.recurrenceService.status("host-R").recurrenceCount());

        AcceptResult m1 = s1.engine.accept(
                new Alert("m1", "host-R", "mem leak detected", "WARN", 0));
        assertEquals(DispositionCode.CREATED, m1.disposition());
        RemediationTask tm = s1.tasks.findById(m1.taskId()).orElseThrow();
        tm.state = TaskState.RETRY_WAIT;
        tm.nextAttemptAt = clock.millis() + 60_000;
        s1.tasks.save(tm);
        log.info("关停现场: task-disk=SUCCEEDED, task-mem=RETRY_WAIT, 复发计数=1");

        // === 进程 2：重启，复发计数不清零、RETRY_WAIT 不被误判为已结束 ===
        clock.advance(200);
        Stack s2 = newStack.get();
        RecurrenceStatus afterRestart = s2.recurrenceService.status("host-R");
        log.info("重启后复发状态: count={} events={}", afterRestart.recurrenceCount(),
                afterRestart.recurrenceEvents());
        assertEquals(1, afterRestart.recurrenceCount(), "重启后复发计数不得清零");

        int resumed = s2.recovery.recoverOnStartup();
        RemediationTask tmAfter = s2.tasks.findById(m1.taskId()).orElseThrow();
        log.info("恢复: resumed={} task-mem state={}", resumed, tmAfter.state);
        assertEquals(1, resumed);
        assertEquals(TaskState.QUEUED, tmAfter.state, "等待重试的修复应重新排队而非写成终态");

        // 等待重试的修复不能被误当成“已结束的修复”：等价告警按在途重复合并
        AcceptResult m2 = s2.engine.accept(
                new Alert("m2", "host-R", "mem leak detected", "WARN", 0));
        log.info("重启后 mem 告警: {} reason='{}'", m2.disposition(), m2.reason());
        assertEquals(DispositionCode.DUPLICATE, m2.disposition());
        assertEquals(m1.taskId(), m2.taskId());

        // 重启后复发继续累计（1 -> 2），且仍受抑制窗口约束
        AcceptResult recur2 = s2.engine.accept(alert("r3", "host-R"));
        log.info("重启后复发: {} reason='{}'", recur2.disposition(), recur2.reason());
        assertEquals(DispositionCode.RECURRENCE_SUPPRESSED, recur2.disposition());
        assertEquals(2, s2.recurrenceService.status("host-R").recurrenceCount());

        // 抑制窗（自 task-disk 终结起 1000ms）过后放行一次新修复
        clock.advance(1_000);
        AcceptResult recur3 = s2.engine.accept(alert("r4", "host-R"));
        log.info("重启后抑制窗滑过: {} reason='{}'", recur3.disposition(), recur3.reason());
        assertEquals(DispositionCode.CREATED, recur3.disposition());
        assertEquals(3, s2.recurrenceService.status("host-R").recurrenceCount());
    }
}
