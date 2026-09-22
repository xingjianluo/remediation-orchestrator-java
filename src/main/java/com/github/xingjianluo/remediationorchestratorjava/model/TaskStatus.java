package com.github.xingjianluo.remediationorchestratorjava.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
/**
 * 修复任务生命周期状态。
 *
 * <p>合法迁移（由 {@link RemediationTask} 聚合根强约束，非法迁移抛
 * {@link IllegalStateException}）：
 * <pre>
 * PENDING            -> QUEUED
 * QUEUED             -> RUNNING | CANCELLED
 * RUNNING            -> WAITING_APPROVAL
 * WAITING_APPROVAL   -> QUEUED(批准后) | APPROVAL_REJECTED | APPROVAL_TIMEOUT | CANCELLED
 * RUNNING            -> SUCCEEDED | FAILED
 * </pre>
 * 只有 5 个终态彼此可区分：SUCCEEDED / FAILED / APPROVAL_REJECTED /
 * APPROVAL_TIMEOUT / CANCELLED。
 */
public enum TaskStatus {
    PENDING(false),
    QUEUED(false),
    RUNNING(false),
    WAITING_APPROVAL(false),
    SUCCEEDED(true),
    FAILED(true),
    APPROVAL_REJECTED(true),
    APPROVAL_TIMEOUT(true),
    CANCELLED(true);

    private final boolean terminal;

    TaskStatus(boolean terminal) {
        this.terminal = terminal;
    }

    @JsonIgnore
    public boolean isTerminal() {
        return terminal;
    }
}
