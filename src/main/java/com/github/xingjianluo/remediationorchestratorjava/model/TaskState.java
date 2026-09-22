package com.github.xingjianluo.remediationorchestratorjava.model;

/** 修复任务的生命周期状态。 */
public enum TaskState {
    QUEUED,
    WAITING_APPROVAL,
    RUNNING,
    RETRY_WAIT,
    SUCCEEDED,
    FAILED,
    APPROVAL_REJECTED,
    APPROVAL_TIMEOUT,
    CANCELLED;

    public boolean isTerminal() {
        return switch (this) {
            case SUCCEEDED, FAILED, APPROVAL_REJECTED, APPROVAL_TIMEOUT, CANCELLED -> true;
            default -> false;
        };
    }
}
