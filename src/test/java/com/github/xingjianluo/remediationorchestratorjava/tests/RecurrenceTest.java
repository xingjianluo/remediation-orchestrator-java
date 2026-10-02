package com.github.xingjianluo.remediationorchestratorjava.tests;

import com.github.xingjianluo.remediationorchestratorjava.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;

import static com.github.xingjianluo.remediationorchestratorjava.tests.TestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 复发处理：修复终结后的等价告警按“抑制窗口 / 受控重开 / 升级人工确认 / 放弃”分流，
 * 与“修复进行中合并（DUPLICATE）”明确区分。
 */
class RecurrenceTest {

    private static final Logger log = LoggerFactory.getLogger(RecurrenceTest.class);

    @TempDir
    Path tempDir;

    private TestFixtures.Env newRecurrenceEnv(List<RemediationPolicy> policies,
                                              long suppressionMillis, long statsWindowMillis,
                                              int threshold) {
        var env = newEnv(policies, tempDir, 1_000_000L);
        env.props.setRecurrenceSuppressionWindowMillis(suppressionMillis);
        env.props.setRecurrenceStatsWindowMillis(statsWindowMillis);
        env.props.setRecurrenceEscalationThreshold(threshold);
        return env;
    }

    private static RemediationTask awaitState(TestFixtures.Env env, String taskId, TaskState state)
            throws InterruptedException {
        await(5000, () -> env.store.findById(taskId).map(t -> t.state == state).orElse(false));
        RemediationTask t = env.store.findById(taskId).orElseThrow();
        log.info("状态迁移: task={} -> {} terminalReason={}", taskId, t.state, t.terminalReason);
        return t;
    }

    @Test
    void recurrenceAfterSuccessIsSuppressedThenReopenedAtMostOnce() throws Exception {
        var policies = List.of(policy("p-disk", List.of("disk full"), false,
                List.of(step(StepType.NOOP, "cleanup"))));
        var env = newRecurrenceEnv(policies, 1000, 100_000, 10);

        env.startDispatcher();
        AcceptResult r1 = env.engine.accept(new Alert("evt-1", "host-A", "disk full", "CRITICAL", 1));
        assertEquals(DispositionCode.CREATED, r1.disposition());
        awaitState(env, r1.taskId(), TaskState.SUCCEEDED);
        env.stopDispatcher();

        // 抑制窗口内复发：不新增修复动作
        AcceptResult r2 = env.engine.accept(new Alert("evt-2", "host-A", "disk full", "CRITICAL", 2));
        log.info("窗口内复发判定: {} reason='{}'", r2.disposition(), r2.reason());
        assertEquals(DispositionCode.RECURRENCE_SUPPRESSED, r2.disposition());
        assertNull(r2.taskId());
        assertTrue(r2.reason().contains("SUCCEEDED"), "原因需体现上次修复结局");
        assertEquals(1, env.store.findByTarget("host-A").size(), "抑制不得新增任务");

        // 窗口过后：放行一次新修复
        env.clock.advance(1001);
        AcceptResult r3 = env.engine.accept(new Alert("evt-3", "host-A", "disk full", "CRITICAL", 3));
        log.info("窗口过后复发判定: {} reason='{}'", r3.disposition(), r3.reason());
        assertEquals(DispositionCode.RECURRENCE_REOPENED, r3.disposition());
        assertNotNull(r3.taskId());
        assertNotEquals(r1.taskId(), r3.taskId());

        // 重开的修复未终结时继续推送：合并，最多放行一次
        AcceptResult r4 = env.engine.accept(new Alert("evt-4", "host-A", "disk full", "CRITICAL", 4));
        log.info("重开后重复推送判定: {} reason='{}'", r4.disposition(), r4.reason());
        assertEquals(DispositionCode.DUPLICATE, r4.disposition());
        assertEquals(r3.taskId(), r4.taskId());
        assertEquals(2, env.store.findByTarget("host-A").size(), "窗口过后最多重开一次");

        boolean audited = env.audit.findAll().stream()
                .anyMatch(e -> AuditEvents.ALERT_RECURRENCE_SUPPRESSED.equals(e.event()))
                && env.audit.findAll().stream()
                .anyMatch(e -> AuditEvents.ALERT_RECURRENCE_REOPENED.equals(e.event()));
        assertTrue(audited, "抑制与重开都必须留审计");
    }

    @Test
    void recurrenceAfterFailureIsDistinguishedFromSuccess() throws Exception {
        var policies = List.of(policy("p-doomed", List.of("boom"), false,
                List.of(step(StepType.ALWAYS_FAIL, "doomed"))));
        var env = newRecurrenceEnv(policies, 1000, 100_000, 10);

        env.startDispatcher();
        AcceptResult r1 = env.engine.accept(new Alert("f-1", "host-F", "boom now", "WARN", 1));
        awaitState(env, r1.taskId(), TaskState.FAILED);
        env.stopDispatcher();

        AcceptResult suppressed = env.engine.accept(new Alert("f-2", "host-F", "boom now", "WARN", 2));
        log.info("失败后再来(窗口内): {} reason='{}'", suppressed.disposition(), suppressed.reason());
        assertEquals(DispositionCode.RECURRENCE_SUPPRESSED, suppressed.disposition());
        assertTrue(suppressed.reason().contains("FAILED"), "原因需区分‘修复失败后又来’");

        env.clock.advance(1001);
        AcceptResult reopened = env.engine.accept(new Alert("f-3", "host-F", "boom now", "WARN", 3));
        log.info("失败后再来(窗口后): {} reason='{}'", reopened.disposition(), reopened.reason());
        assertEquals(DispositionCode.RECURRENCE_REOPENED, reopened.disposition());
        assertTrue(reopened.reason().contains("FAILED"));
    }

    @Test
    void suppressionWindowBoundaryIsExact() throws Exception {
        var policies = List.of(policy("p-disk", List.of("disk full"), false,
                List.of(step(StepType.NOOP, "cleanup"))));
        var env = newRecurrenceEnv(policies, 1000, 100_000, 10);

        env.startDispatcher();
        AcceptResult r1 = env.engine.accept(new Alert("b-1", "host-B", "disk full", "WARN", 1));
        RemediationTask done = awaitState(env, r1.taskId(), TaskState.SUCCEEDED);
        env.stopDispatcher();
        long suppressedUntil = done.finishedAt + 1000;
        log.info("上次终结 finishedAt={} 抑制窗口到 {}", done.finishedAt, suppressedUntil);

        // 边界前一毫秒：仍抑制
        env.clock.set(suppressedUntil - 1);
        AcceptResult before = env.engine.accept(new Alert("b-2", "host-B", "disk full", "WARN", 2));
        log.info("边界-1ms 判定: {}", before.disposition());
        assertEquals(DispositionCode.RECURRENCE_SUPPRESSED, before.disposition());

        // 恰好到达边界：放行
        env.clock.set(suppressedUntil);
        AcceptResult atBoundary = env.engine.accept(new Alert("b-3", "host-B", "disk full", "WARN", 3));
        log.info("边界时刻判定: {}", atBoundary.disposition());
        assertEquals(DispositionCode.RECURRENCE_REOPENED, atBoundary.disposition());
    }

    @Test
    void escalationRequiresConfirmationAndReleasesWhenWindowSlides() throws Exception {
        var policies = List.of(policy("p-disk", List.of("disk full"), false,
                List.of(step(StepType.NOOP, "cleanup"))));
        var env = newRecurrenceEnv(policies, 10, 50_000, 2);
        env.startDispatcher();

        // 两轮修复都成功
        AcceptResult r1 = env.engine.accept(new Alert("e-1", "host-E", "disk full", "WARN", 1));
        awaitState(env, r1.taskId(), TaskState.SUCCEEDED);
        env.clock.advance(11);
        AcceptResult r2 = env.engine.accept(new Alert("e-2", "host-E", "disk full", "WARN", 2));
        assertEquals(DispositionCode.RECURRENCE_REOPENED, r2.disposition());
        awaitState(env, r2.taskId(), TaskState.SUCCEEDED);
        env.clock.advance(11);

        // 第三次复发：达到阈值，升级人工确认，无任何副作用
        AcceptResult r3 = env.engine.accept(new Alert("e-3", "host-E", "disk full", "WARN", 3));
        log.info("达阈值复发判定: {} reason='{}'", r3.disposition(), r3.reason());
        assertEquals(DispositionCode.RECURRENCE_ESCALATED, r3.disposition());
        assertNull(r3.taskId());
        assertEquals(2, env.store.findByTarget("host-E").size(), "升级前不得新增任务");
        assertTrue(env.store.findNonTerminal().isEmpty(), "升级前不得有任何待执行副作用");

        // 查询可见：升级状态、复发计数、统计窗
        var status = env.recurrence.statusForTarget("host-E", env.clock.millis());
        assertEquals(1, status.size());
        var s = status.get(0);
        log.info("升级状态查询: escalated={} count={}/{} window={}ms", s.escalated(),
                s.recurrenceCount(), s.escalationThreshold(), s.statsWindowMillis());
        assertTrue(s.escalated());
        assertEquals(2, s.recurrenceCount());
        assertEquals(50_000, s.statsWindowMillis());
        assertEquals(2, s.escalationThreshold());

        // 人工确认后放行
        env.recurrence.confirm(s.fingerprint(), "oncall", "ack known issue", env.clock.millis());
        AcceptResult r4 = env.engine.accept(new Alert("e-4", "host-E", "disk full", "WARN", 4));
        log.info("确认后复发判定: {} reason='{}'", r4.disposition(), r4.reason());
        assertEquals(DispositionCode.RECURRENCE_REOPENED, r4.disposition());
        assertTrue(r4.reason().contains("confirmed"));
        awaitState(env, r4.taskId(), TaskState.SUCCEEDED);
        env.clock.advance(11);

        // 确认只覆盖当时计数：又修一轮仍复发 → 重新升级
        AcceptResult r5 = env.engine.accept(new Alert("e-5", "host-E", "disk full", "WARN", 5));
        log.info("确认后再复发判定: {} reason='{}'", r5.disposition(), r5.reason());
        assertEquals(DispositionCode.RECURRENCE_ESCALATED, r5.disposition());

        // 统计窗滑过后升级自然解除
        env.clock.advance(60_000);
        var slid = env.recurrence.statusForTarget("host-E", env.clock.millis()).get(0);
        log.info("窗口滑动后: escalated={} count={}", slid.escalated(), slid.recurrenceCount());
        assertFalse(slid.escalated(), "旧记录滑出统计窗后升级必须自然解除");
        assertEquals(0, slid.recurrenceCount());
        AcceptResult r6 = env.engine.accept(new Alert("e-6", "host-E", "disk full", "WARN", 6));
        log.info("窗口滑动后复发判定: {} reason='{}'", r6.disposition(), r6.reason());
        assertEquals(DispositionCode.RECURRENCE_REOPENED, r6.disposition());
        env.stopDispatcher();
    }

    @Test
    void abandonBlocksAutoRemediationUntilConfirmedOrWindowSlides() throws Exception {
        var policies = List.of(policy("p-disk", List.of("disk full"), false,
                List.of(step(StepType.NOOP, "cleanup"))));
        var env = newRecurrenceEnv(policies, 10, 50_000, 1);
        env.startDispatcher();

        AcceptResult r1 = env.engine.accept(new Alert("a-1", "host-X", "disk full", "WARN", 1));
        awaitState(env, r1.taskId(), TaskState.SUCCEEDED);
        env.clock.advance(11);
        env.stopDispatcher();

        // 阈值 1：一次修复后即升级
        AcceptResult escalated = env.engine.accept(new Alert("a-2", "host-X", "disk full", "WARN", 2));
        assertEquals(DispositionCode.RECURRENCE_ESCALATED, escalated.disposition());

        // 人工放弃：之后一律不再自动修复
        var s = env.recurrence.statusForTarget("host-X", env.clock.millis()).get(0);
        env.recurrence.abandon(s.fingerprint(), "oncall", "known noise, give up", env.clock.millis());
        AcceptResult abandoned = env.engine.accept(new Alert("a-3", "host-X", "disk full", "WARN", 3));
        log.info("放弃后复发判定: {} reason='{}'", abandoned.disposition(), abandoned.reason());
        assertEquals(DispositionCode.RECURRENCE_ABANDONED, abandoned.disposition());
        assertNull(abandoned.taskId());
        assertEquals(1, env.store.findByTarget("host-X").size(), "放弃后不得新增任务");

        // 人工仍可改判确认：确认后放行
        env.recurrence.confirm(s.fingerprint(), "lead", "reconsidered", env.clock.millis());
        AcceptResult reopened = env.engine.accept(new Alert("a-4", "host-X", "disk full", "WARN", 4));
        log.info("放弃后改判确认: {} reason='{}'", reopened.disposition(), reopened.reason());
        assertEquals(DispositionCode.RECURRENCE_REOPENED, reopened.disposition());
    }
}
