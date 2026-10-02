package com.github.xingjianluo.remediationorchestratorjava.model;

/** 审计事件类型常量。 */
public final class AuditEvents {
    private AuditEvents() {
    }

    public static final String ALERT_ACCEPTED = "ALERT_ACCEPTED";
    public static final String ALERT_DUPLICATE = "ALERT_DUPLICATE";
    public static final String ALERT_MAINTENANCE_SUPPRESSED = "ALERT_MAINTENANCE_SUPPRESSED";
    public static final String ALERT_NO_POLICY = "ALERT_NO_POLICY";
    public static final String TASK_QUEUED = "TASK_QUEUED";
    public static final String TASK_DISPATCHED = "TASK_DISPATCHED";
    public static final String APPROVAL_REQUESTED = "APPROVAL_REQUESTED";
    public static final String APPROVAL_GRANTED = "APPROVAL_GRANTED";
    public static final String APPROVAL_REJECTED = "APPROVAL_REJECTED";
    public static final String APPROVAL_TIMEOUT = "APPROVAL_TIMEOUT";
    public static final String TASK_CANCELLED = "TASK_CANCELLED";
    public static final String STEP_STARTED = "STEP_STARTED";
    public static final String STEP_SUCCEEDED = "STEP_SUCCEEDED";
    public static final String STEP_FAILED = "STEP_FAILED";
    public static final String STEP_RETRY_SCHEDULED = "STEP_RETRY_SCHEDULED";
    public static final String TASK_SUCCEEDED = "TASK_SUCCEEDED";
    public static final String TASK_FAILED = "TASK_FAILED";
    public static final String RECOVERY_SCAN = "RECOVERY_SCAN";
    public static final String RECOVERY_RESUMED = "RECOVERY_RESUMED";
    public static final String ALERT_RECURRENCE_SUPPRESSED = "ALERT_RECURRENCE_SUPPRESSED";
    public static final String ALERT_RECURRENCE_ESCALATED = "ALERT_RECURRENCE_ESCALATED";
    public static final String ALERT_RECURRENCE_AFTER_FAILURE = "ALERT_RECURRENCE_AFTER_FAILURE";
    public static final String ALERT_RECURRENCE_ABANDONED = "ALERT_RECURRENCE_ABANDONED";
    public static final String ALERT_RECURRENCE_REOPENED = "ALERT_RECURRENCE_REOPENED";
    public static final String RECURRENCE_CONFIRMED = "RECURRENCE_CONFIRMED";
    public static final String RECURRENCE_ABANDON = "RECURRENCE_ABANDON";
}
