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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 场景：高风险任务已进入 WAITING_APPROVAL 时进程重启——恢复后仍处待审批，
 * 批准前不执行，批准后正常完成。
 */
class ApprovalRecoveryTest {

    private static final Logger log = LoggerFactory.getLogger(ApprovalRecoveryTest.class);

    private RemediationStrategy highStrategy() {
        return new RemediationStrategy("ar-strategy", 10, RiskLevel.HIGH,
                new AlertMatcher("svc-", "SERVICE_DOWN", "KEYWORD", "down"),
                List.of(StepSpec.of("ar-step", "test.action", 1_000)),
                1, 0);
    }

    @Test
    void waitingApprovalTaskSurvivesRestartAndStillRequiresApproval(@TempDir Path tmp) {
        var action = new RecordingAction("test.action");
        var strategies = List.of(highStrategy());
        TestEngine e1 = TestEngine.create(tmp, Map.of("test.action", action), strategies);

        Alert alert = new Alert(UUID.randomUUID().toString(), "svc-ar", "SERVICE_DOWN", "down");
        log.info("[TEST] INPUT high-risk alert={}", alert.alertId());
        String taskId = e1.intake.accept(alert).taskId();

        await().atMost(Duration.ofSeconds(5)).until(() ->
                e1.taskStore.findById(taskId)
                        .map(t -> t.getStatus() == TaskStatus.WAITING_APPROVAL).orElse(false));
        log.info("[TEST] DECISION task WAITING_APPROVAL pre-restart, runs={} (must be 0)",
                action.countOf("ar-step"));
        assertEquals(0, action.countOf("ar-step"));

        // 等待审批期间“正常关闭并重启”（不是崩溃，因为审批等待本身不占执行资源）
        log.info("[TEST] INPUT restart while WAITING_APPROVAL");
        TestEngine e2 = e1.restart(strategies, 2, 60_000);

        var restored = e2.taskStore.findById(taskId).orElseThrow();
        log.info("[TEST] ASSERT post-restart status={} (still waiting), runs={}",
                restored.getStatus(), action.countOf("ar-step"));
        assertEquals(TaskStatus.WAITING_APPROVAL, restored.getStatus());
        assertEquals(0, action.countOf("ar-step"), "恢复后批准前仍不得执行");
        assertTrue(e2.audit.byTask(taskId).stream()
                .anyMatch(a -> a.type() == AuditType.TASK_RECOVERED));

        e2.approvals.grant(taskId);
        await().atMost(Duration.ofSeconds(5)).until(() ->
                e2.taskStore.findById(taskId)
                        .map(t -> t.getStatus() == TaskStatus.SUCCEEDED).orElse(false));
        log.info("[TEST] ASSERT granted after restart -> SUCCEEDED, runs={}",
                action.countOf("ar-step"));
        assertEquals(1, action.countOf("ar-step"));

        e2.close();
    }
}
