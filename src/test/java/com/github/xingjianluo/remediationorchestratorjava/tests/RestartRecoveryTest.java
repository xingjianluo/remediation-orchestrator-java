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
 * 场景：任务执行到一半（step1 已完成、step2 进行中）时“进程被杀”（立即 shutdownNow），
 * 用同一数据目录重启后：任务从 step2 继续，已完成的 step1 绝不重复执行。
 */
class RestartRecoveryTest {

    private static final Logger log = LoggerFactory.getLogger(RestartRecoveryTest.class);

    private RemediationStrategy twoStepStrategy() {
        return new RemediationStrategy("recover-strategy", 10, RiskLevel.LOW,
                new AlertMatcher(null, "DISK_FULL", "KEYWORD", "disk"),
                List.of(
                        StepSpec.of("step-1", "test.action", 2_000),
                        StepSpec.of("step-2", "test.action", 2_000),
                        StepSpec.of("step-3", "test.action", 2_000)),
                1, 0);
    }

    @Test
    void interruptedRunningTaskResumesWithoutReRunningCompletedSteps(@TempDir Path tmp) throws Exception {
        var action = new RecordingAction("test.action");
        var strategies = List.of(twoStepStrategy());
        TestEngine engine1 = TestEngine.create(tmp, Map.of("test.action", action),
                strategies, 2, 60_000, false);

        Alert alert = new Alert(UUID.randomUUID().toString(),
                "host-recover", "DISK_FULL", "disk full");
        log.info("[TEST] INPUT alert={} (3-step low-risk task)", alert.alertId());
        String taskId = engine1.intake.accept(alert).taskId();

        // 阻塞在 step-2（意味着 step-1 已成功落盘）
        action.blockOn("step-2");
        assertTrue(action.awaitNEntered("step-2", 1, 5_000),
                "step-2 应当在 step-1 成功后开始");

        await().atMost(Duration.ofSeconds(5)).until(() ->
                engine1.taskStore.findById(taskId)
                        .map(t -> t.getStatus() == TaskStatus.RUNNING).orElse(false));
        log.info("[TEST] DECISION task RUNNING with step-1 SUCCEEDED on disk; now simulating crash");
        assertEquals(1, action.countOf("step-1"));

        // 模拟进程被杀死：立即中断工作线程，不做正常收尾
        engine1.dispatcher.shutdown();
        engine1.executor.shutdown();
        engine1.approvals.shutdown();

        var onDisk = engine1.taskStore.findById(taskId).orElseThrow();
        log.info("[TEST] DECISION on-disk pre-restart status={}, step-1 status={}",
                onDisk.getStatus(), onDisk.getSteps().get(0).getStatus());
        assertEquals(TaskStatus.RUNNING, onDisk.getStatus());

        // 同数据目录“重启”，触发启动恢复
        log.info("[TEST] INPUT restart engine on same data dir -> recoverAll()");
        TestEngine engine2 = engine1.restart(strategies, 2, 60_000);

        await().atMost(Duration.ofSeconds(10)).until(() ->
                engine2.taskStore.findById(taskId)
                        .map(t -> t.getStatus() == TaskStatus.SUCCEEDED).orElse(false));

        var recovered = engine2.taskStore.findById(taskId).orElseThrow();
        log.info("[TEST] ASSERT final status={}, runs: step-1={} step-2={} step-3={}",
                recovered.getStatus(), action.countOf("step-1"),
                action.countOf("step-2"), action.countOf("step-3"));
        assertEquals(TaskStatus.SUCCEEDED, recovered.getStatus());
        assertEquals(1, action.countOf("step-1"), "已完成步骤恢复后不得重复执行");
        assertTrue(action.countOf("step-2") >= 1, "中断时进行中的步骤可在恢复后重新执行");
        assertEquals(1, action.countOf("step-3"));

        boolean recoveredAudit = engine2.audit.byTask(taskId).stream()
                .anyMatch(e -> e.type() == AuditType.TASK_RECOVERED);
        log.info("[TEST] ASSERT TASK_RECOVERED audit present={}", recoveredAudit);
        assertTrue(recoveredAudit, "恢复动作必须有审计记录");

        engine2.close();
    }
}
