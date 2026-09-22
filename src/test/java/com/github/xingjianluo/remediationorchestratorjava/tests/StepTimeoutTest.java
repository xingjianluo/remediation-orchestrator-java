package com.github.xingjianluo.remediationorchestratorjava.tests;

import com.github.xingjianluo.remediationorchestratorjava.action.ActionResult;
import com.github.xingjianluo.remediationorchestratorjava.action.RemediationAction;
import com.github.xingjianluo.remediationorchestratorjava.model.Alert;
import com.github.xingjianluo.remediationorchestratorjava.model.AlertMatcher;
import com.github.xingjianluo.remediationorchestratorjava.model.AuditType;
import com.github.xingjianluo.remediationorchestratorjava.model.DispositionReason;
import com.github.xingjianluo.remediationorchestratorjava.model.RemediationStrategy;
import com.github.xingjianluo.remediationorchestratorjava.model.RiskLevel;
import com.github.xingjianluo.remediationorchestratorjava.model.StepSpec;
import com.github.xingjianluo.remediationorchestratorjava.model.TaskStatus;
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

/** 场景：单步执行超过 timeoutMs 被判定超时；经重试仍超时后任务失败且审计可定位。 */
class StepTimeoutTest {

    private static final Logger log = LoggerFactory.getLogger(StepTimeoutTest.class);

    /** 始终阻塞超过超时时间的动作。 */
    static class SlowAction implements RemediationAction {
        @Override
        public String name() {
            return "slow.action";
        }

        @Override
        public ActionResult execute(Context context) throws Exception {
            Thread.sleep(5_000);
            return ActionResult.ok();
        }
    }

    @Test
    void stepTimingOutIsRetriedThenFailsWithTrace(@TempDir Path tmp) {
        var slow = new SlowAction();
        RemediationStrategy strategy = new RemediationStrategy("slow-strategy", 10,
                RiskLevel.LOW, new AlertMatcher(null, "LAG", "KEYWORD", "lag"),
                List.of(StepSpec.of("slow-step", "slow.action", 200)),
                2, 10);
        Map<String, RemediationAction> beans = Map.of("slow.action", slow);
        try (TestEngine engine = TestEngine.create(tmp, beans, List.of(strategy))) {

            Alert alert = new Alert(UUID.randomUUID().toString(), "host-lag", "LAG", "lag high");
            log.info("[TEST] INPUT alert={}, step timeout=200ms, maxAttempts=2, action sleeps 5s",
                    alert.alertId());
            String taskId = engine.intake.accept(alert).taskId();

            // 同时验证无法匹配策略的告警被可区分地拒绝且不建任务
            Alert unmatched = new Alert(UUID.randomUUID().toString(), "host-x", "WEIRD", "???");
            log.info("[TEST] INPUT unmatched alert={} type=WEIRD", unmatched.alertId());
            var rejected = engine.intake.accept(unmatched);
            assertEquals(DispositionReason.REJECTED_NO_STRATEGY, rejected.reason());
            assertTrue(engine.audit.all().stream()
                    .anyMatch(a -> a.type() == AuditType.ALERT_REJECTED
                            && unmatched.alertId().equals(a.alertId())));
            log.info("[TEST] DECISION unmatched -> REJECTED_NO_STRATEGY with audit trace");

            await().atMost(Duration.ofSeconds(10)).until(() ->
                    engine.taskStore.findById(taskId)
                            .map(t -> t.getStatus() == TaskStatus.FAILED).orElse(false));
            var task = engine.taskStore.findById(taskId).orElseThrow();
            log.info("[TEST] ASSERT status={} attempts={} summary='{}'",
                    task.getStatus(), task.getSteps().get(0).getAttempts(),
                    task.getFailureSummary());
            assertEquals(TaskStatus.FAILED, task.getStatus());
            assertEquals(2, task.getSteps().get(0).getAttempts(), "超时后仍按上限重试");
            assertTrue(task.getFailureSummary().contains("timeout"),
                    "失败信息需包含超时原因");
            assertTrue(engine.audit.byTask(taskId).stream()
                    .anyMatch(a -> a.type() == AuditType.STEP_TIMEOUT));
            log.info("[TEST] ASSERT STEP_TIMEOUT audit present");
        }
    }
}
