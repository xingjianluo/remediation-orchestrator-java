package com.github.xingjianluo.remediationorchestratorjava.model;

/** 审批状态：拒绝 / 超时 / 取消彼此可区分。 */
public enum ApprovalStatus {
    NONE, PENDING, APPROVED, REJECTED, TIMED_OUT, CANCELLED;

    public boolean isDecided() {
        return this != NONE && this != PENDING;
    }
}
