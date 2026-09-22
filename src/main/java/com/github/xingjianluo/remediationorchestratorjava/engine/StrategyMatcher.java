package com.github.xingjianluo.remediationorchestratorjava.engine;

import com.github.xingjianluo.remediationorchestratorjava.model.Alert;
import com.github.xingjianluo.remediationorchestratorjava.model.RemediationStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 策略匹配：在所有匹配告警的策略中，按 priority 升序（数值小优先级高）选择；
 * 优先级相同按 id 字典序，保证选择结果确定。
 */
public class StrategyMatcher {

    private static final Logger log = LoggerFactory.getLogger(StrategyMatcher.class);

    private final List<RemediationStrategy> strategies;

    public StrategyMatcher(List<RemediationStrategy> strategies) {
        this.strategies = List.copyOf(strategies);
    }

    public Optional<RemediationStrategy> match(Alert alert) {
        List<RemediationStrategy> hits = strategies.stream()
                .filter(s -> s.matches(alert))
                .sorted(Comparator.comparingInt(RemediationStrategy::priority)
                        .thenComparing(RemediationStrategy::id))
                .toList();
        if (hits.isEmpty()) {
            log.info("[MATCH] alert={} target={} type={} -> no strategy matched",
                    alert.alertId(), alert.target(), alert.type());
            return Optional.empty();
        }
        RemediationStrategy chosen = hits.get(0);
        log.info("[MATCH] alert={} matched {} candidate(s), chosen strategy={} risk={}",
                alert.alertId(), hits.size(), chosen.id(), chosen.riskLevel());
        return Optional.of(chosen);
    }

    public List<RemediationStrategy> strategies() {
        return strategies;
    }
}
