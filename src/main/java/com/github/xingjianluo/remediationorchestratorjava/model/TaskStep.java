package com.github.xingjianluo.remediationorchestratorjava.model;

import java.util.Map;

/**
 * 任务内一个步骤的可变运行态，按策略 {@link StepSpec} 生成并随任务一起持久化。
 */
public class TaskStep {

    private String name;
    private String actionName;
    private long timeoutMs;
    private Map<String, String> params = Map.of();
    private StepStatus status = StepStatus.PENDING;
    private int attempts;
    private String lastError;

    public TaskStep() {
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getActionName() { return actionName; }
    public void setActionName(String actionName) { this.actionName = actionName; }

    public long getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(long timeoutMs) { this.timeoutMs = timeoutMs; }

    public Map<String, String> getParams() { return params; }
    public void setParams(Map<String, String> params) {
        this.params = params == null ? Map.of() : Map.copyOf(params);
    }

    public StepStatus getStatus() { return status; }
    public void setStatus(StepStatus status) { this.status = status; }

    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }

    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
}
