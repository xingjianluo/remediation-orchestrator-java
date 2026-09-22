package com.github.xingjianluo.remediationorchestratorjava.tests;

import com.github.xingjianluo.remediationorchestratorjava.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;

import static com.github.xingjianluo.remediationorchestratorjava.tests.TestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** 重复 / 等价告警只触发一次修复，处理结论与原因可区分。 */
class DuplicateAlertTest {

    private static final Logger log = LoggerFactory.getLogger(DuplicateAlertTest.class);

    @TempDir
    Path tempDir;

    @Test
    void duplicateAlertWhileTaskOpenReturnsDuplicateWithDistinctReason() {
        var policies = List.of(policy("p-disk", List.of("disk full"), false,
                List.of(step(StepType.NOOP, "s1"))));
        var env = newEnv(policies, tempDir);
        Alert a1 = new Alert("evt-1", "host-A", "Disk Full now", "CRITICAL", 100);
        Alert a2 = new Alert("evt-2", "host-A", "disk full   now ", "CRITICAL", 101); // 大小写/空白差异
        Alert a3 = new Alert("evt-3", "host-B", "disk full now", "CRITICAL", 102);   // 不同目标

        AcceptResult r1 = env.engine.accept(a1);
        AcceptResult r2 = env.engine.accept(a2);
        AcceptResult r3 = env.engine.accept(a3);

        log.info("输入告警: {} | {} | {}", a1.id(), a2.id(), a3.id());
        log.info("判定: {} reason='{}'", r1.disposition(), r1.reason());
        log.info("判定: {} reason='{}'", r2.disposition(), r2.reason());
        log.info("判定: {} reason='{}'", r3.disposition(), r3.reason());

        assertEquals(DispositionCode.CREATED, r1.disposition());
        assertEquals(DispositionCode.DUPLICATE, r2.disposition());
        assertEquals(r1.taskId(), r2.taskId());
        assertTrue(r2.reason().contains("open task"));
        assertEquals(DispositionCode.CREATED, r3.disposition());
        assertNotEquals(r1.taskId(), r3.taskId());

        // 同一指纹只允许一个未终结任务
        assertEquals(1, env.store.findByTarget("host-A").size());
        // 审计中重复结论可追溯
        boolean duplicateAudited = env.audit.findAll().stream()
                .anyMatch(e -> AuditEvents.ALERT_DUPLICATE.equals(e.event())
                        && "evt-2".equals(e.alertId()));
        assertTrue(duplicateAudited, "duplicate disposition must be audited");
    }

    @Test
    void noPolicyAndDisabledPolicyAreRejected() {
        RemediationPolicy disabled = policy("p-off", List.of("disk"), false, List.of());
        disabled.enabled = false;
        var env = newEnv(List.of(disabled), tempDir);

        AcceptResult noPolicy = env.engine.accept(
                new Alert("x1", "h", "unknown symptom", "WARN", 1));
        AcceptResult disabledHit = env.engine.accept(
                new Alert("x2", "h", "disk noise", "WARN", 1));

        log.info("noPolicy={} reason='{}'", noPolicy.disposition(), noPolicy.reason());
        log.info("disabledHit={} reason='{}'", disabledHit.disposition(), disabledHit.reason());
        assertEquals(DispositionCode.REJECTED_NO_POLICY, noPolicy.disposition());
        assertEquals(DispositionCode.REJECTED_NO_POLICY, disabledHit.disposition());
    }
}
