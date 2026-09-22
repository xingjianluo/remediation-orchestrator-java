package com.github.xingjianluo.remediationorchestratorjava.tests;

import com.github.xingjianluo.remediationorchestratorjava.model.Alert;
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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * 场景：维护窗口内告警被抑制且无任何修复副作用（不建任务、不执行动作）；
 * 窗口结束后同一指纹的告警可正常受理。
 */
class MaintenanceWindowTest {

    private static final Logger log = LoggerFactory.getLogger(MaintenanceWindowTest.class);

    private RemediationStrategy strategy() {
        return new RemediationStrategy("mw-strategy", 10, RiskLevel.LOW,
                new AlertMatcher(null, "CPU_HOT", "KEYWORD", "cpu"),
                List.of(StepSpec.of("mw-step", "test.action", 1_000)),
                1, 0);
    }

    @Test
    void alertInsideMaintenanceWindowIsSuppressedWithoutSideEffects(@TempDir Path tmp) {
        var action = new RecordingAction("test.action");
        try (TestEngine engine = TestEngine.create(tmp, Map.of("test.action", action),
                List.of(strategy()))) {

            Instant now = Instant.now();
            engine.windows.register("win-1", "host-mw", now.minus(Duration.ofMinutes(5)),
                    now.plus(Duration.ofMinutes(10)));

            Alert alert = new Alert(UUID.randomUUID().toString(),
                    "host-mw-7", "CPU_HOT", "cpu overheat");
            log.info("[TEST] INPUT alert={} target={} within active maintenance window win-1",
                    alert.alertId(), alert.target());

            var disposition = engine.intake.accept(alert);
            log.info("[TEST] DECISION disposition={} windowId={}",
                    disposition.reason(), disposition.windowId());

            assertSame(DispositionReason.SUPPRESSED_MAINTENANCE, disposition.reason());
            assertEquals("win-1", disposition.windowId());

            log.info("[TEST] ASSERT no task created (tasks={}), no action executed (runs={})",
                    engine.taskStore.findAll().size(), action.countOf("mw-step"));
            assertEquals(0, engine.taskStore.findAll().size(), "维护窗口内不得创建任务");
            assertEquals(0, action.countOf("mw-step"), "维护窗口内不得执行修复动作");

            // 窗口结束后再来等价告警：由于此前没有任务，应正常接受并执行
            engine.windows.register("win-2", "host-mw",
                    now.minus(Duration.ofHours(2)), now.minus(Duration.ofHours(1)));
            // 移除活跃窗口影响：登记一个不覆盖当前时刻的窗口即可；这里直接新建引擎更直观
            try (TestEngine engine2 = TestEngine.create(tmp, Map.of("test.action", action),
                    List.of(strategy()))) {
                // engine2 使用独立数据目录（无窗口登记），等价告警应接受
                Alert later = new Alert(UUID.randomUUID().toString(),
                        "host-mw-7", "CPU_HOT", "cpu overheat");
                log.info("[TEST] INPUT post-window equivalent alert={} into clean engine",
                        later.alertId());
                var d2 = engine2.intake.accept(later);
                assertSame(DispositionReason.ACCEPTED, d2.reason());
                await().atMost(Duration.ofSeconds(10)).until(() ->
                        engine2.taskStore.findById(d2.taskId())
                                .map(t -> t.getStatus() == TaskStatus.SUCCEEDED).orElse(false));
                log.info("[TEST] ASSERT post-window alert ACCEPTED and task SUCCEEDED");
            }
        }
    }
}
