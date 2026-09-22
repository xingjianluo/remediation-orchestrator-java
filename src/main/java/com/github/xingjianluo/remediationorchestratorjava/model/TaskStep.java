package com.github.xingjianluo.remediationorchestratorjava.model;

import java.util.Map;

/** 任务中一个可持久化、可恢复的步骤实例。 */
public class TaskStep {
    public int index;
    public StepType type;
    public String name;
    public Map<String, String> params = Map.of();
    public StepState state = StepState.PENDING;
    public int attempts;
    public String lastError;
    public long updatedAt;
}
