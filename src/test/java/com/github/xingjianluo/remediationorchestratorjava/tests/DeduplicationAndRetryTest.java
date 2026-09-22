package com.github.xingjianluo.remediationorchestratorjava.tests;

import com.github.xingjianluo.remediationorchestratorjava.model.Alert;
import com.github.xingjianluo.remediationorchestratorjava.model.AlertDisposition;
import com.github.xingjianluo.remediationorchestratorjava.model.AlertMatcher;
import com.github.xingjianluo.remediationorchestratorjava.model.DispositionReason;
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
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * 场景：重复等价告警的抑制/合并语义，以及步骤重试达到上限后任务失败且信息可定位。
 */
class DeduplicationAndRetryTest {

    private static final Logger log = LoggerFactory.getLogger(DeduplicationAndRetryTest.class);

    private RemediationStrategy lowStrategy(RecordingAction action) {
        return new RemediationStrategy("dup-strategy", 10, RiskLevel.LOW,
                new AlertMatcher(null, "DISK_FULL", "KEYWORD", "disk"),
                List.of(StepSpec.of("step-a", "test.action", 1_000)),
                3, 10);
    }

    @Test
    void equivalentAlertsMergeIntoOneTaskAndNeverTriggerSecondRemediation(@TempDir Path tmp) {
        var action = new RecordingAction("test.action");
        try (TestEngine engine = TestEngine.create(tmp, Map.of("test.action", action),
                List.of(lowStrategy(action)))) {

            Alert first = new Alert("a1", "host-1", "DISK_FULL", "disk full");
            Alert second = new Alert("a2", "host-1", "DISK_FULL", "  disk   full "); // 规范化后等价
            Alert third = new Alert("a3", "host-1", "DISK_FULL", "disk full");

            log.info("[TEST] INPUT first={} second={} third={} (equivalent after normalize)",
                    first.alertId(), second.alertId(), third.alertId());

            AlertDisposition d1 = engine.intake.accept(first);
            AlertDisposition d2 = engine.intake.accept(second);
            AlertDisposition d3 = engine.intake.accept(third);

            log.info("[TEST] DECISION d1={} task={}, d2={} task={}, d3={} task={}",
                    d1.reason(), d1.taskId(), d2.reason(), d2.taskId(), d3.reason(), d3.taskId());

            assertEquals(DispositionReason.ACCEPTED, d1.reason());
            assertSame(DispositionReason.DUPLICATE_MERGED, d2.reason());
            assertSame(DispositionReason.DUPLICATE_MERGED, d3.reason());
            assertEquals(d1.taskId(), d2.taskId());
            assertEquals(d1.taskId(), d3.taskId());

            String taskId = d1.taskId();
            await().atMost(java.time.Duration.ofSeconds(10))
                    .until(() -> engine.taskStore.findById(taskId)
                            .map(t -> t.getStatus().isTerminal()).orElse(false));

            var task = engine.taskStore.findById(taskId).orElseThrow();
            log.info("[TEST] ASSERT final status={}, mergedCount={}, actionRuns={}",
                    task.getStatus(), task.getMergedDuplicateCount(),
                    action.countOf("step-a"));
            assertEquals(TaskStatus.SUCCEEDED, task.getStatus());
            assertEquals(2, task.getMergedDuplicateCount());
            assertEquals(1, action.countOf("step-a"), "修复步骤只能执行一次");

            // 任务终态后再来等价告警 -> DUPLICATE_TERMINAL，不再修复
            Alert fourth = new Alert("a4", "host-1", "DISK_FULL", "disk full");
            AlertDisposition d4 = engine.intake.accept(fourth);
            log.info("[TEST] DECISION post-terminal alert={} -> {}",
                    fourth.alertId(), d4.reason());
            assertSame(DispositionReason.DUPLICATE_TERMINAL, d4.reason());
            assertEquals(1, action.countOf("step-a"), "终态后等价告警不得再次触发修复");
        }
    }

    @Test
    void stepRetriesUpToLimitThenTaskFailsWithDiagnosticInfo(@TempDir Path tmp) {
        var action = new RecordingAction("test.action");
        action.failFirstTimes("step-a", 5, "persistent disk error");
        try (TestEngine engine = TestEngine.create(tmp, Map.of("test.action", action),
                List.of(lowStrategy(action)))) {

            Alert alert = new Alert(UUID.randomUUID().toString(),
                    "host-9", "DISK_FULL", "disk full");
            log.info("[TEST] INPUT alert={} target={} strategy maxAttempts=3, action always fails",
                    alert.alertId(), alert.target());

            AlertDisposition d = engine.intake.accept(alert);
            String taskId = d.taskId();
            assertNotNull(taskId);

            await().atMost(java.time.Duration.ofSeconds(10))
                    .until(() -> engine.taskStore.findById(taskId)
                            .map(t -> t.getStatus().isTerminal()).orElse(false));

            var task = engine.taskStore.findById(taskId).orElseThrow();
            log.info("[TEST] ASSERT status={} attempts={} failureSummary='{}'",
                    task.getStatus(),
                    task.getSteps().get(0).getAttempts(),
                    task.getFailureSummary());

            assertEquals(TaskStatus.FAILED, task.getStatus());
            assertEquals(3, task.getSteps().get(0).getAttempts(), "最多尝试 3 次");
            assertEquals(3, action.countOf("step-a"));
            assertNotNull(task.getFailureSummary());
            // 失败信息足以定位：含步骤名、尝试次数、末次错误
            assertEquals(true, task.getFailureSummary().contains("step 'step-a'"));
            assertEquals(true, task.getFailureSummary().contains("3 attempt(s)"));
            assertEquals(true, task.getFailureSummary().contains("persistent disk error"));
        }
    }
}
