package com.github.xingjianluo.remediationorchestratorjava.engine;

import com.github.xingjianluo.remediationorchestratorjava.model.Alert;
import com.github.xingjianluo.remediationorchestratorjava.model.RemediationPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

/** 按告警内容与严重级别顺序匹配第一条启用的策略。 */
public class PolicyMatcher {

    private static final Logger log = LoggerFactory.getLogger(PolicyMatcher.class);

    private static final List<String> SEVERITY_ORDER = List.of("INFO", "WARN", "CRITICAL");

    private final List<RemediationPolicy> policies;

    public PolicyMatcher(List<RemediationPolicy> policies) {
        this.policies = List.copyOf(policies);
    }

    public Optional<MatchedPolicy> match(Alert alert) {
        String content = alert.content() == null ? "" : alert.content();
        String contentLower = content.toLowerCase();
        log.info("[match] input alertId={}, target={}, severity={}, content='{}'",
                alert.id(), alert.target(), alert.severity(), content);

        for (RemediationPolicy policy : policies) {
            if (!policy.enabled) {
                log.info("[match] policy={} skipped: disabled", policy.id);
                continue;
            }
            String keywordHit = null;
            if (policy.matchAnyKeywords == null || policy.matchAnyKeywords.isEmpty()) {
                keywordHit = "<wildcard>";
            } else {
                for (String keyword : policy.matchAnyKeywords) {
                    if (keyword != null && contentLower.contains(keyword.toLowerCase())) {
                        keywordHit = keyword;
                        break;
                    }
                }
            }
            if (keywordHit == null) {
                log.info("[match] policy={} skipped: no keyword in content", policy.id);
                continue;
            }
            if (!severityAtLeast(alert.severity(), policy.minSeverity)) {
                log.info("[match] policy={} skipped: severity {} below required {}",
                        policy.id, alert.severity(), policy.minSeverity);
                continue;
            }
            String reason = "keywords=" + policy.matchAnyKeywords + ", hit='" + keywordHit
                    + "', severity=" + alert.severity()
                    + (policy.minSeverity == null ? "" : " >= " + policy.minSeverity);
            log.info("[match] policy={} matched; basis: {}", policy.id, reason);
            return Optional.of(new MatchedPolicy(policy, reason));
        }
        log.info("[match] no policy matched for alertId={}", alert.id());
        return Optional.empty();
    }

    static boolean severityAtLeast(String actual, String required) {
        if (required == null || required.isBlank()) {
            return true;
        }
        if (actual == null) {
            return false;
        }
        int a = SEVERITY_ORDER.indexOf(actual.toUpperCase());
        int r = SEVERITY_ORDER.indexOf(required.toUpperCase());
        if (a < 0 || r < 0) {
            return actual.equalsIgnoreCase(required);
        }
        return a >= r;
    }

    /** 命中结果及其判定依据（用于日志与审计）。 */
    public record MatchedPolicy(RemediationPolicy policy, String reason) {
    }
}
