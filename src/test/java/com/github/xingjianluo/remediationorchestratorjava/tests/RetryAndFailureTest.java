package com.github.xingjianluo.remediationorchestratorjava.tests;

import com.github.xingjianluo.remediationorchestratorjava.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static com.github.xingjianluo.remediationorchestratorjava.tests.TestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** 步骤重试：次数上限 + 线性退避；彻底失败时终态与错误信息可定位。 */
class RetryAndFailureTest {

    private static final Logger log = LoggerFactory.getLogger(RetryAndFailureTest.class);

    @TempDir
    Path tempDir;

    @Test
    void flakyStepSucceedsWithinRetryBudget() throws Exception {
        // FLAKY failFirst=1：第 1 次失败、第 2 次成功；策略允许 1 次重试
        var policies = List.of(policy("p-flaky", List.of("flaky"), false,
                List.of(step(StepType.FLAKY, "flaky-step", Map.of("failFirst", "1")))));
        policies.get(0).maxRetries = 1;
        policies.get(0).backoffBaseMillis = 20;
        var env = newEnv(policies, tempDir);
        env.startDispatcher();

        String id = env.engine.accept(new Alert("f1", "host-F", "flaky thing", "WARN", 1)).taskId();
        await(3000, () -> env.store.findById(id).orElseThrow().state == TaskState.SUCCEEDED);
        RemediationTask task = env.store.findById(id).orElseThrow();
        TaskStep step = task.steps.get(0);
        log.info("关键迁移: step attempts={} state={} -> task={}", step.attempts, step.state, task.state);
        assertEquals(2, step.attempts);
        assertEquals(StepState.SUCCEEDED, step.state);
        // 审计中可看到一次重试排期
        assertTrue(env.audit.findByTaskId(id).stream()
                .anyMatch(e -> AuditEvents.STEP_RETRY_SCHEDULED.equals(e.event())));
        env.stopDispatcher();
    }

    @Test
    void alwaysFailExhaustsRetriesAndRecordsDiagnostics() throws Exception {
        var policies = List.of(policy("p-fail", List.of("boom"), false,
                List.of(step(StepType.ALWAYS_FAIL, "doomed", Map.of("message", "disk controller dead")))));
        policies.get(0).maxRetries = 2;
        policies.get(0).backoffBaseMillis = 10;
        var env = newEnv(policies, tempDir);
        env.startDispatcher();

        String id = env.engine.accept(new Alert("f2", "host-D", "boom now", "WARN", 1)).taskId();
        await(3000, () -> env.store.findById(id).orElseThrow().state == TaskState.FAILED);

        RemediationTask task = env.store.findById(id).orElseThrow();
        TaskStep step = task.steps.get(0);
        log.info("输入策略 maxRetries=2；最终 task.state={} terminalReason='{}'", task.state, task.terminalReason);
        assertEquals(TaskState.FAILED, task.state);
        assertEquals(3, step.attempts, "首次 + 2 次重试，共 3 次尝试");
        assertEquals(StepState.FAILED, step.state);
        assertTrue(task.terminalReason.contains("disk controller dead"), "终态原因必须包含底层错误");
        assertTrue(task.terminalReason.contains("after 3 attempts"));

        long failedEvents = env.audit.findByTaskId(id).stream()
                .filter(e -> AuditEvents.STEP_FAILED.equals(e.event())).count();
        assertEquals(3, failedEvents);
        assertTrue(env.audit.findByTaskId(id).stream()
                .anyMatch(e -> AuditEvents.TASK_FAILED.equals(e.event())));
        env.stopDispatcher();
    }

    @Test
    void stepTimeoutCountsAsFailureAndIsRetriedThenFailsTerminal() throws Exception {
        // SLOW 2s 但超时 100ms，两次尝试都超时 -> FAILED
        var policies = List.of(policy("p-slow", List.of("slow"), false,
                List.of(step(StepType.SLOW, "too-slow", Map.of("sleepMillis", "2000")))));
        policies.get(0).maxRetries = 1;
        policies.get(0).backoffBaseMillis = 10;
        policies.get(0).stepTimeoutMillis = 100;
        var env = newEnv(policies, tempDir);
        env.startDispatcher();

        long t0 = System.currentTimeMillis();
        String id = env.engine.accept(new Alert("f3", "host-T", "slow thing", "WARN", 1)).taskId();
        await(5000, () -> env.store.findById(id).orElseThrow().state == TaskState.FAILED);
        long elapsed = System.currentTimeMillis() - t0;
        RemediationTask task = env.store.findById(id).orElseThrow();
        log.info("超时失败: state={} attempts={} reason='{}' elapsedMs={}",
                task.state, task.steps.get(0).attempts, task.terminalReason, elapsed);
        assertEquals(TaskState.FAILED, task.state);
        assertTrue(task.terminalReason.contains("timeout"));
        // 两次 2s 睡眠被中断，总耗时应远小于 4s（证明超时真正中断了执行）
        assertTrue(elapsed < 1500, "timeout must interrupt the running step, elapsed=" + elapsed);
        env.stopDispatcher();
    }
}
