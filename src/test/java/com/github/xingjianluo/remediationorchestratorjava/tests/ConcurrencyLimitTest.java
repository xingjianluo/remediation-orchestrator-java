package com.github.xingjianluo.remediationorchestratorjava.tests;

import com.github.xingjianluo.remediationorchestratorjava.model.Alert;
import com.github.xingjianluo.remediationorchestratorjava.model.AlertMatcher;
import com.github.xingjianluo.remediationorchestratorjava.model.AuditType;
import com.github.xingjianluo.remediationorchestratorjava.model.RemediationStrategy;
import com.github.xingjianluo.remediationorchestratorjava.model.RiskLevel;
import com.github.xingjianluo.remediationorchestratorjava.model.StepSpec;
import com.github.xingjianluo.remediationorchestratorjava.model.TaskStatus;
import com.github.xingjianluo.remediationorchestratorjava.support.RecordingAction;
import com.github.xingjianluo.remediationorchestratorjava.support.TestEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 场景：全局并发超限时排队而非丢弃；同一目标的修复任务严格串行。
 * 四个任务执行同一个共享步骤，阻塞门按“步骤+任务”独立，靠执行日志中的 taskId 区分。
 */
class ConcurrencyLimitTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrencyLimitTest.class);

    private RemediationStrategy sharedStrategy() {
        return new RemediationStrategy("conc-strategy", 10, RiskLevel.LOW,
                new AlertMatcher(null, "LOAD_HIGH", "ANY", null),
                List.of(StepSpec.of("shared-step", "test.action", 5_000)),
                1, 0);
    }

    @Test
    void tasksBeyondGlobalConcurrencyQueueAndAreNeverDropped(@TempDir Path tmp) throws Exception {
        var action = new RecordingAction("test.action");
        action.blockOn("shared-step");
        try (TestEngine engine = TestEngine.create(tmp, Map.of("test.action", action),
                List.of(sharedStrategy()), 2, 60_000, false)) {

            log.info("[TEST] INPUT 4 alerts with distinct targets, globalConcurrency=2");
            for (int i = 1; i <= 4; i++) {
                engine.intake.accept(new Alert("alert-g" + i, "host-g" + i,
                        "LOAD_HIGH", "high load " + i));
            }

            assertTrue(action.awaitNEntered("shared-step", 2, 3_000),
                    "应当有两个不同任务进入执行");

            await().atMost(java.time.Duration.ofSeconds(5))
                    .until(() -> engine.dispatcher.activeCount() == 2);

            long runningOrEntered = action.executionLog().stream()
                    .filter(e -> e.startsWith("shared-step")).distinct().count();
            log.info("[TEST] DECISION exactly 2 tasks dispatched (active=2, distinctEntered={}), "
                    + "queueDepth={}", runningOrEntered, engine.dispatcher.queueDepth());
            assertEquals(2, engine.dispatcher.activeCount());
            assertEquals(2, runningOrEntered, "超出全局并发上限的任务必须排队，不得执行");

            action.releaseAll("shared-step");

            await().atMost(java.time.Duration.ofSeconds(10)).until(() ->
                    engine.taskStore.findAll().size() == 4
                            && engine.taskStore.findAll().stream()
                            .allMatch(t -> t.getStatus() == TaskStatus.SUCCEEDED));
            log.info("[TEST] ASSERT all 4 queued tasks eventually SUCCEEDED, none dropped");
        }
    }

    @Test
    void tasksOnSameTargetExecuteSerially(@TempDir Path tmp) throws Exception {
        var action = new RecordingAction("test.action");
        action.blockOn("shared-step");
        try (TestEngine engine = TestEngine.create(tmp, Map.of("test.action", action),
                List.of(sharedStrategy()), 4, 60_000, false)) {

            log.info("[TEST] INPUT 2 alerts on SAME target host-same, globalConcurrency=4");
            engine.intake.accept(new Alert("alert-x1", "host-same", "LOAD_HIGH", "load 1"));
            engine.intake.accept(new Alert("alert-x2", "host-same", "LOAD_HIGH", "load 2"));

            assertTrue(action.awaitNEntered("shared-step", 1, 3_000));
            await().atMost(java.time.Duration.ofSeconds(3))
                    .until(() -> engine.dispatcher.activeCount() == 1);

            long enteredWhileFirstHeld = action.executionLog().stream()
                    .filter(e -> e.startsWith("shared-step")).distinct().count();
            log.info("[TEST] DECISION only 1 task dispatched on same target (active=1, entered={})",
                    enteredWhileFirstHeld);
            assertEquals(1, engine.dispatcher.activeCount());
            assertEquals(1, enteredWhileFirstHeld, "同目标第二个任务不得与第一个并发");

            action.releaseAll("shared-step");

            await().atMost(java.time.Duration.ofSeconds(10)).until(() ->
                    engine.taskStore.findAll().size() == 2
                            && engine.taskStore.findAll().stream()
                            .allMatch(t -> t.getStatus() == TaskStatus.SUCCEEDED));
            log.info("[TEST] ASSERT both same-target tasks SUCCEEDED serially; stepStart audit count={}",
                    engine.audit.all().stream().filter(e -> e.type() == AuditType.STEP_START).count());
            assertEquals(2, engine.audit.all().stream()
                    .filter(e -> e.type() == AuditType.STEP_START).count());
        }
    }
}
