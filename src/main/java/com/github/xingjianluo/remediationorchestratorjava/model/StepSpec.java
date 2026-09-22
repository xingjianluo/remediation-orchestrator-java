package com.github.xingjianluo.remediationorchestratorjava.model;

import java.util.Map;
import java.util.Objects;

/**
 * 策略中声明的一个修复步骤：actionName 对应 Spring 容器中
 * {@link com.github.xingjianluo.remediationorchestratorjava.action.RemediationAction}
 * 的 bean 名称；timeoutMs 为单次执行超时。
 */
public record StepSpec(String name, String actionName, long timeoutMs, Map<String, String> params) {
    public StepSpec {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(actionName, "actionName");
        params = params == null ? Map.of() : Map.copyOf(params);
        if (timeoutMs <= 0) {
            throw new IllegalArgumentException("timeoutMs must be positive");
        }
    }

    public static StepSpec of(String name, String actionName, long timeoutMs) {
        return new StepSpec(name, actionName, timeoutMs, Map.of());
    }

    public static StepSpec of(String name, String actionName, long timeoutMs, Map<String, String> params) {
        return new StepSpec(name, actionName, timeoutMs, params);
    }
}
