package com.github.xingjianluo.remediationorchestratorjava.tests;

import com.github.xingjianluo.remediationorchestratorjava.config.MaintenanceWindow;
import com.github.xingjianluo.remediationorchestratorjava.maintenance.MaintenanceWindowService;
import com.github.xingjianluo.remediationorchestratorjava.model.AcceptResult;
import com.github.xingjianluo.remediationorchestratorjava.model.Alert;
import com.github.xingjianluo.remediationorchestratorjava.model.DispositionCode;
import com.github.xingjianluo.remediationorchestratorjava.model.StepType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;

import static com.github.xingjianluo.remediationorchestratorjava.tests.TestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** 维护窗口内的告警被抑制：不建任务、无副作用，且与其它处置可区分。 */
class MaintenanceWindowTest {

    private static final Logger log = LoggerFactory.getLogger(MaintenanceWindowTest.class);

    @TempDir
    Path tempDir;

    @Test
    void alertInsideWindowIsSuppressedWithoutSideEffects() {
        var policies = List.of(policy("p", List.of("disk"), false,
                List.of(step(StepType.MARKER, "mark"))));
        var env = newEnv(policies, tempDir, 1_000_000L);

        MaintenanceWindow window = env.maintenance.addWindow("host-A", 900_000L, 2_000_000L, "kernel patching");
        log.info("维护窗口: {}", window);

        Alert alert = new Alert("m-1", "host-A", "disk full", "CRITICAL", 1_500_000L);
        AcceptResult result = env.engine.accept(alert);

        log.info("输入: {}", alert);
        log.info("判定: {} reason='{}'", result.disposition(), result.reason());
        assertEquals(DispositionCode.SUPPRESSED_MAINTENANCE, result.disposition());
        assertNull(result.taskId());
        assertTrue(result.reason().contains(window.id()));
        assertTrue(env.store.findAll().isEmpty(), "suppressed alert must not create a task");
        assertTrue(env.audit.findAll().stream()
                .anyMatch(e -> "ALERT_MAINTENANCE_SUPPRESSED".equals(e.event())));

        // 窗口外同目标告警正常建任务
        env.clock.set(2_100_000L);
        AcceptResult after = env.engine.accept(
                new Alert("m-2", "host-A", "disk full", "CRITICAL", 2_100_000L));
        log.info("窗口后判定: {} reason='{}'", after.disposition(), after.reason());
        assertEquals(DispositionCode.CREATED, after.disposition());
    }

    @Test
    void wildcardWindowSuppressesAllTargets() {
        var policies = List.of(policy("p", List.of("disk"), false,
                List.of(step(StepType.NOOP, "s"))));
        var env = newEnv(policies, tempDir, 500L);
        env.maintenance.addWindow("*", 0L, 1_000L, "global freeze");

        AcceptResult r = env.engine.accept(new Alert("g-1", "any-host", "disk", "WARN", 500L));
        assertEquals(DispositionCode.SUPPRESSED_MAINTENANCE, r.disposition());
    }

    @Test
    void windowsPersistToFileAndReload() {
        Path file = tempDir.resolve("mw.json");
        MaintenanceWindowService s1 = new MaintenanceWindowService();
        s1.loadFromFile(file);
        s1.addWindow("host-X", 1, 2, "test");

        MaintenanceWindowService s2 = new MaintenanceWindowService();
        s2.loadFromFile(file);
        List<MaintenanceWindow> reloaded = s2.listWindows();
        assertEquals(1, reloaded.size());
        assertTrue(s2.isSuppressed("host-X", 1));
        assertFalse(s2.isSuppressed("host-X", 2), "窗口为左闭右开");
    }
}
