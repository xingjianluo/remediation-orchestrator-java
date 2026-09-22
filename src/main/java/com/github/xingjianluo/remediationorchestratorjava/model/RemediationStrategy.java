package com.github.xingjianluo.remediationorchestratorjava.model;

import java.util.List;
import java.util.Objects;

/**
 * 修复策略（不可变）。高风险（{@link RiskLevel#HIGH}）策略生成的任务在执行前必须人工批准。
 * maxAttempts 为每个步骤的最大尝试次数（含首次），retryBackoffMs 为重试固定退避。
 */
public record RemediationStrategy(String id, int priority, RiskLevel riskLevel,
                                  AlertMatcher matcher, List<StepSpec> steps,
                                  int maxAttempts, long retryBackoffMs) {
    public RemediationStrategy {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(riskLevel, "riskLevel");
        Objects.requireNonNull(matcher, "matcher");
        steps = List.copyOf(steps);
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("strategy must declare at least one step");
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
        if (retryBackoffMs < 0) {
            throw new IllegalArgumentException("retryBackoffMs must be >= 0");
        }
    }

    public boolean matches(Alert alert) {
        return matcher.matches(alert);
    }
}
