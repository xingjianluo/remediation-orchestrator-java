package com.github.xingjianluo.remediationorchestratorjava.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
/**
 * 单个修复步骤的状态。只有 SUCCEEDED 是终态；RUNNING 在崩溃恢复时重置为 PENDING，
 * 因此已 SUCCEEDED 的步骤在恢复后绝不会重复执行。
 */
public enum StepStatus {
    PENDING(false),
    RUNNING(false),
    SUCCEEDED(true),
    FAILED(true);

    private final boolean terminal;

    StepStatus(boolean terminal) {
        this.terminal = terminal;
    }

    @JsonIgnore
    public boolean isTerminal() {
        return terminal;
    }
}
