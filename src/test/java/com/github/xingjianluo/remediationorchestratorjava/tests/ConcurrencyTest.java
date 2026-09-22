package com.github.xingjianluo.remediationorchestratorjava.tests;

import com.github.xingjianluo.remediationorchestratorjava.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.github.xingjianluo.remediationorchestratorjava.tests.TestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** 并发编排：同目标串行；超过全局上限时排队等待，绝不丢弃。 */
class ConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrencyTest.class);

    @TempDir
    Path tempDir;

    @Test
    void sameTargetExecutesSerially() throws Exception {
        // SLOW 300ms；若同目标并行，第二个任务不会等第一个完成
        var policies = List.of(policy("p-slow", List.of("slow"), false,
                List.of(step(StepType.SLOW, "slow", Map.of("sleepMillis", "300")))));
        var env = newEnv(policies, tempDir);
        env.props.setMaxConcurrent(4);
        env.startDispatcher();

        String t1 = env.engine.accept(new Alert("c1", "host-S", "slow", "WARN", 1)).taskId();
        Thread.sleep(80); // 等第一个任务进入 RUNNING
        String t2 = env.engine.accept(new Alert("c2", "host-S", "slow symptom differs", "WARN", 2)).taskId();
        log.info("输入同目标任务 t1={} t2={}", t1, t2);

        // t1 运行期间 t2 必须仍在排队
        await(500, () -> env.store.findById(t1).orElseThrow().state == TaskState.RUNNING);
        assertEquals(TaskState.QUEUED, env.store.findById(t2).orElseThrow().state,
                "同目标第二个任务必须等待");
        log.info("关键迁移: t1=RUNNING 时 t2={} (串行成立)",
                env.store.findById(t2).orElseThrow().state);

        await(3000, () -> env.store.findById(t1).orElseThrow().state == TaskState.SUCCEEDED);
        await(3000, () -> env.store.findById(t2).orElseThrow().state == TaskState.SUCCEEDED);

        // t2 必须在 t1 完成之后才开始
        long t1Finish = env.store.findById(t1).orElseThrow().finishedAt;
        long t2Start = env.store.findById(t2).orElseThrow().startedAt;
        log.info("时序: t1.finishedAt={} <= t2.startedAt={}", t1Finish, t2Start);
        assertTrue(t2Start >= t1Finish - 50, "同目标任务必须串行，实际 t2Start=" + t2Start);

        env.stopDispatcher();
    }

    @Test
    void tasksBeyondGlobalCapQueueInsteadOfDropping() throws Exception {
        var policies = List.of(policy("p-slow", List.of("slow"), false,
                List.of(step(StepType.SLOW, "slow", Map.of("sleepMillis", "400")))));
        var env = newEnv(policies, tempDir);
        env.props.setMaxConcurrent(2);

        List<String> ids = new ArrayList<>();
        // 4 个不同目标、全局上限 2
        for (int i = 1; i <= 4; i++) {
            String id = env.engine.accept(
                    new Alert("g" + i, "host-" + i, "slow issue number " + i, "WARN", i)).taskId();
            ids.add(id);
        }
        env.startDispatcher();
        log.info("提交 4 个任务，全局上限=2: {}", ids);

        await(400, () -> env.store.findAll().stream()
                .filter(t -> t.state == TaskState.RUNNING).count() == 2);
        long queued = env.store.findAll().stream().filter(t -> t.state == TaskState.QUEUED).count();
        log.info("关键状态: RUNNING=2 时 QUEUED={}（排队而非丢弃）", queued);
        assertEquals(2, queued);

        await(5000, () -> env.store.findAll().stream()
                .allMatch(t -> t.state == TaskState.SUCCEEDED));
        assertEquals(4, env.store.findByState(TaskState.SUCCEEDED).size());
        log.info("最终状态: 4/4 SUCCEEDED，无任务被丢弃");
        env.stopDispatcher();
    }
}
