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
    /**
     * 下一次应执行的尝试序号（0 = 首次执行）。排期重试时持久化，
     * 重启恢复后从这里继续，已累计的重试进度不会清零；
     * 执行中途被崩溃/关停打断的尝试不推进该序号，恢复后原地重跑。
     */
    public int nextAttempt;
    public String lastError;
    public long updatedAt;
}
