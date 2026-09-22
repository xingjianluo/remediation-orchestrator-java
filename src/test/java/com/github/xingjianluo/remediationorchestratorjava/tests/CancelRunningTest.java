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

/** 运行中主动取消：当前步骤被中断，任务收束到唯一 CANCELLED 终态，无 RUNNING 残留。 */
class CancelRunningTest {

    private static final Logger log = LoggerFactory.getLogger(CancelRunningTest.class);

    @TempDir
    Path tempDir;

    @Test
    void cancelInterruptsRunningSlowStepAndClosesCleanly() throws Exception {
        var policies = List.of(policy("p", List.of("slow"), false, List.of(
                step(StepType.SLOW, "slow-1", Map.of("sleepMillis", "5000")),
                step(StepType.MARKER, "after-cancel-marker"))));
        var env = newEnv(policies, tempDir);
        env.props.setMaxConcurrent(1);
        env.startDispatcher();

        String id = env.engine.accept(new Alert("r1", "host-C", "slow please", "WARN", 1)).taskId();
        await(500, () -> env.store.findById(id).orElseThrow().state == TaskState.RUNNING);
        log.info("关键状态: task=RUNNING，发起取消");

        assertTrue(env.engine.cancel(id, "operator pressed stop"));
        await(2000, () -> env.store.findById(id).orElseThrow().state == TaskState.CANCELLED);

        RemediationTask task = env.store.findById(id).orElseThrow();
        log.info("取消后状态: state={} steps={} reason='{}'",
                task.state,
                task.steps.stream().map(s -> s.index + ":" + s.state).toList(),
                task.terminalReason);
        assertEquals(TaskState.CANCELLED, task.state);
        assertEquals(StepState.CANCELLED, task.steps.get(0).state);
        assertEquals(StepState.CANCELLED, task.steps.get(1).state, "未开始的步骤也必须收束为 CANCELLED");
        assertTrue(task.steps.stream().noneMatch(s -> s.state == StepState.RUNNING));
        assertTrue(task.finishedAt > 0);
        env.stopDispatcher();
    }

    @Test
    void cancelQueuedTaskNeverStarts() throws Exception {
        // 上限 1：第一个长任务占满槽位，第二个任务排队期间取消
        var policies = List.of(policy("p", List.of("slow"), false,
                List.of(step(StepType.SLOW, "s", Map.of("sleepMillis", "2000")))));
        var env = newEnv(policies, tempDir);
        env.props.setMaxConcurrent(1);
        env.startDispatcher();

        env.engine.accept(new Alert("q1", "host-1", "slow one", "WARN", 1));
        String second = env.engine.accept(new Alert("q2", "host-2", "slow two", "WARN", 2)).taskId();
        await(500, () -> env.store.findById(second).orElseThrow().state == TaskState.QUEUED);
        assertTrue(env.engine.cancel(second, "dequeued"));

        Thread.sleep(300);
        RemediationTask task = env.store.findById(second).orElseThrow();
        log.info("排队取消: state={} startedAt={}", task.state, task.startedAt);
        assertEquals(TaskState.CANCELLED, task.state);
        assertEquals(0, task.startedAt, "排队中被取消的任务不得开始执行");
        env.stopDispatcher();
    }
}
