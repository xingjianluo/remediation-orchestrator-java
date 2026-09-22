package com.github.xingjianluo.remediationorchestratorjava.tests;

import com.github.xingjianluo.remediationorchestratorjava.engine.PolicyMatcher;
import com.github.xingjianluo.remediationorchestratorjava.model.Alert;
import com.github.xingjianluo.remediationorchestratorjava.model.RemediationPolicy;
import com.github.xingjianluo.remediationorchestratorjava.model.StepSpec;
import com.github.xingjianluo.remediationorchestratorjava.model.StepType;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class PolicyMatcherTest {

    private static final Logger log = LoggerFactory.getLogger(PolicyMatcherTest.class);

    private RemediationPolicy policy(String id, List<String> keywords, String minSeverity, boolean enabled) {
        RemediationPolicy p = new RemediationPolicy();
        p.id = id;
        p.matchAnyKeywords = keywords;
        p.minSeverity = minSeverity;
        p.enabled = enabled;
        StepSpec step = new StepSpec();
        step.type = StepType.NOOP;
        step.name = "s";
        p.steps = List.of(step);
        return p;
    }

    @Test
    void matchesByKeywordAndSeverity() {
        PolicyMatcher matcher = new PolicyMatcher(List.of(
                policy("p-disk", List.of("disk full"), "WARN", true),
                policy("p-any", List.of(), null, true)));
        Alert alert = new Alert("a1", "host-1", "DISK FULL on /var", "CRITICAL", 1L);
        log.info("test input: {}", alert);

        Optional<PolicyMatcher.MatchedPolicy> hit = matcher.match(alert);

        assertTrue(hit.isPresent());
        assertEquals("p-disk", hit.get().policy().id);
        log.info("判定依据: {}", hit.get().reason());
        assertTrue(hit.get().reason().contains("disk full"));
    }

    @Test
    void skipsWhenSeverityTooLowAndFallsThroughToWildcard() {
        PolicyMatcher matcher = new PolicyMatcher(List.of(
                policy("p-disk", List.of("disk full"), "CRITICAL", true),
                policy("p-any", List.of(), null, true)));
        Alert alert = new Alert("a2", "host-1", "disk full", "INFO", 1L);

        Optional<PolicyMatcher.MatchedPolicy> hit = matcher.match(alert);

        assertTrue(hit.isPresent());
        assertEquals("p-any", hit.get().policy().id);
    }

    @Test
    void skipsDisabledPolicies() {
        PolicyMatcher matcher = new PolicyMatcher(List.of(
                policy("p-disk", List.of("disk"), null, false)));
        Alert alert = new Alert("a3", "h", "disk noise", "WARN", 1L);

        assertTrue(matcher.match(alert).isEmpty());
    }
}
