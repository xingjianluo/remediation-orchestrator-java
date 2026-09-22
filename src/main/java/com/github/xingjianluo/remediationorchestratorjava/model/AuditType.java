package com.github.xingjianluo.remediationorchestratorjava.model;

/** 审计事件类型，覆盖受理判定、状态迁移、审批与恢复等关键事实。 */
public enum AuditType {
    ALERT_ACCEPTED,
    ALERT_DUPLICATE,
    ALERT_SUPPRESSED,
    ALERT_REJECTED,
    TASK_CREATED,
    TASK_QUEUED,
    TASK_RUNNING,
    TASK_SUCCEEDED,
    TASK_FAILED,
    TASK_CANCELLED,
    STEP_START,
    STEP_RETRY,
    STEP_SUCCEEDED,
    STEP_FAILED,
    STEP_TIMEOUT,
    APPROVAL_WAITING,
    APPROVAL_GRANTED,
    APPROVAL_REJECTED,
    APPROVAL_TIMEOUT,
    TASK_RECOVERED
}
