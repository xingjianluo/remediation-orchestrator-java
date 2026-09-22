package com.github.xingjianluo.remediationorchestratorjava.tests;

import com.github.xingjianluo.remediationorchestratorjava.model.Alert;
import com.github.xingjianluo.remediationorchestratorjava.model.AlertMatcher;
import com.github.xingjianluo.remediationorchestratorjava.model.AuditType;
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
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 场景：高风险修复批准前不得执行；批准、拒绝、超时、主动取消四种结局彼此可区分，
 * 且不会留下半完成状态。
 */
class ApprovalInterceptTest {

    private static final Logger log = LoggerFactory.getLogger(ApprovalInterceptTest.class);

    private RemediationStrategy highStrategy() {
        return new RemediationStrategy("high-strategy", 10, RiskLevel.HIGH,
                new AlertMatcher("svc-", "SERVICE_DOWN", "KEYWORD", "down"),
                List.of(StepSpec.of("risky-step", "test.action", 1_000)),
                1, 0);
    }

    private Alert newAlert(String target) {
        return new Alert(UUID.randomUUID().toString(), target, "SERVICE_DOWN", "service down");
    }

    @Test
    void highRiskTaskDoesNotExecuteBeforeApprovalThenRunsAfterGrant(@TempDir Path tmp) {
        var action = new RecordingAction("test.action");
        try (TestEngine engine = TestEngine.create(tmp, Map.of("test.action", action),
                List.of(highStrategy()))) {

            var d = engine.intake.accept(newAlert("svc-app"));
            assertEquals(DispositionReason.ACCEPTED, d.reason());
            String taskId = d.taskId();
            log.info("[TEST] INPUT high-risk alert -> task={}", taskId);

            await().atMost(Duration.ofSeconds(5)).until(() ->
                    engine.taskStore.findById(taskId)
                            .map(t -> t.getStatus() == TaskStatus.WAITING_APPROVAL)
                            .orElse(false));
            log.info("[TEST] DECISION task WAITING_APPROVAL, action runs={} (must be 0)",
                    action.countOf("risky-step"));
            assertEquals(0, action.countOf("risky-step"), "批准前高风险步骤不得执行");

            engine.approvals.grant(taskId);
            await().atMost(Duration.ofSeconds(5)).until(() ->
                    engine.taskStore.findById(taskId)
                            .map(t -> t.getStatus() == TaskStatus.SUCCEEDED).orElse(false));
            log.info("[TEST] ASSERT granted -> task SUCCEEDED, action runs={}",
                    action.countOf("risky-step"));
            assertEquals(1, action.countOf("risky-step"));
        }
    }

    @Test
    void rejectionIsDistinctTerminalAndLeavesNoPartialWork(@TempDir Path tmp) {
        var action = new RecordingAction("test.action");
        try (TestEngine engine = TestEngine.create(tmp, Map.of("test.action", action),
                List.of(highStrategy()))) {
            String taskId = engine.intake.accept(newAlert("svc-r")).taskId();
            await().atMost(Duration.ofSeconds(5)).until(() ->
                    engine.taskStore.findById(taskId)
                            .map(t -> t.getStatus() == TaskStatus.WAITING_APPROVAL).orElse(false));

            engine.approvals.reject(taskId);
            var task = engine.taskStore.findById(taskId).orElseThrow();
            log.info("[TEST] DECISION reject -> status={}", task.getStatus());
            assertSame(TaskStatus.APPROVAL_REJECTED, task.getStatus());
            assertEquals(0, action.countOf("risky-step"), "拒绝后不得执行任何步骤");
            assertTrue(task.getSteps().stream()
                    .allMatch(s -> s.getStatus() == com.github.xingjianluo.remediationorchestratorjava.model.StepStatus.PENDING));
            log.info("[TEST] ASSERT rejected terminal, no partial steps, audit has REJECTED={}",
                    engine.audit.byTask(taskId).stream()
                            .anyMatch(e -> e.type() == AuditType.APPROVAL_REJECTED));
            assertTrue(engine.audit.byTask(taskId).stream()
                    .anyMatch(e -> e.type() == AuditType.APPROVAL_REJECTED));
        }
    }

    @Test
    void approvalTimeoutIsDistinctFromRejectAndCancel(@TempDir Path tmp) {
        var action = new RecordingAction("test.action");
        try (TestEngine engine = TestEngine.create(tmp, Map.of("test.action", action),
                List.of(highStrategy()), 2, 200, false)) {
            String taskId = engine.intake.accept(newAlert("svc-t")).taskId();
            log.info("[TEST] INPUT high-risk task={}, approvalTimeout=200ms, never approve",
                    taskId);

            await().atMost(Duration.ofSeconds(5)).until(() ->
                    engine.taskStore.findById(taskId)
                            .map(t -> t.getStatus() == TaskStatus.APPROVAL_TIMEOUT)
                            .orElse(false));
            var task = engine.taskStore.findById(taskId).orElseThrow();
            log.info("[TEST] ASSERT timeout -> status={} (distinct terminal), runs={}",
                    task.getStatus(), action.countOf("risky-step"));
            assertSame(TaskStatus.APPROVAL_TIMEOUT, task.getStatus());
            assertEquals(0, action.countOf("risky-step"));
        }
    }

    @Test
    void activeCancelIsDistinctTerminal(@TempDir Path tmp) {
        var action = new RecordingAction("test.action");
        try (TestEngine engine = TestEngine.create(tmp, Map.of("test.action", action),
                List.of(highStrategy()))) {
            String taskId = engine.intake.accept(newAlert("svc-c")).taskId();
            await().atMost(Duration.ofSeconds(5)).until(() ->
                    engine.taskStore.findById(taskId)
                            .map(t -> t.getStatus() == TaskStatus.WAITING_APPROVAL).orElse(false));

            engine.approvals.cancel(taskId);
            var task = engine.taskStore.findById(taskId).orElseThrow();
            log.info("[TEST] ASSERT cancel -> status={}, distinct from rejected/timeout",
                    task.getStatus());
            assertSame(TaskStatus.CANCELLED, task.getStatus());
            assertEquals(0, action.countOf("risky-step"));
            assertTrue(engine.audit.byTask(taskId).stream()
                    .anyMatch(e -> e.type() == AuditType.TASK_CANCELLED));
        }
    }

    @Test
    void queuedTaskCanBeCancelledAndNeverRuns(@TempDir Path tmp) throws Exception {
        var action = new com.github.xingjianluo.remediationorchestratorjava.support.RecordingAction("test.action");
        // 低风险策略：并发上限=1，先占用唯一名额，第二个任务排队
        var low = new RemediationStrategy("cancel-strategy", 10, RiskLevel.LOW,
                new AlertMatcher(null, "LOAD_HIGH", "ANY", null),
                List.of(StepSpec.of("cancel-shared", "test.action", 5_000)), 1, 0);
        try (TestEngine engine = TestEngine.create(tmp,
                Map.of("test.action", action), List.of(low), 1, 60_000, false)) {

            action.blockOn("cancel-shared");
            engine.intake.accept(new Alert("c-run", "host-c1", "LOAD_HIGH", "load"));
            assertTrue(action.awaitNEntered("cancel-shared", 1, 3_000));

            var d = engine.intake.accept(new Alert("c-wait", "host-c2", "LOAD_HIGH", "load"));
            String queuedId = d.taskId();
            log.info("[TEST] INPUT second task={} queued behind running (concurrency=1)", queuedId);
            await().atMost(Duration.ofSeconds(3))
                    .until(() -> engine.dispatcher.queueDepth() >= 1);

            engine.approvals.cancel(queuedId);
            var task = engine.taskStore.findById(queuedId).orElseThrow();
            log.info("[TEST] ASSERT queued task cancelled -> status={}", task.getStatus());
            assertSame(TaskStatus.CANCELLED, task.getStatus());

            action.releaseAll("cancel-shared");
            // 给一点时间确认被取消任务不会被执行
            Thread.sleep(300);
            boolean queuedTaskRan = engine.audit.byTask(queuedId).stream()
                    .anyMatch(a -> a.type() == AuditType.STEP_START);
            log.info("[TEST] ASSERT cancelled queued task never started (stepStart={})",
                    queuedTaskRan);
            assertEquals(false, queuedTaskRan);
        }
    }
}
