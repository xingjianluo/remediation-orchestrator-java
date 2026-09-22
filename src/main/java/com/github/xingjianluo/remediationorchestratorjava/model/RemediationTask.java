package com.github.xingjianluo.remediationorchestratorjava.model;

import java.util.ArrayList;
import java.util.List;

/** 一次修复任务的完整持久化状态。 */
public class RemediationTask {
    public String id;
    /** 去重指纹：策略 + 目标 + 规范化告警键。 */
    public String fingerprint;
    public String policyId;
    public String target;
    public Alert alert;
    public TaskState state = TaskState.QUEUED;
    public ApprovalStatus approval = ApprovalStatus.NONE;
    public String terminalReason;
    public List<TaskStep> steps = new ArrayList<>();
    public int attempts;
    /** 生效的步骤最大重试次数（-1 表示运行时取全局默认）。 */
    public int maxRetries = -1;
    /** 生效的退避基数毫秒（-1 表示运行时取全局默认）。 */
    public long backoffBaseMillis = -1;
    /** 生效的单步骤超时毫秒（-1 表示运行时取全局默认）。 */
    public long stepTimeoutMillis = -1;
    public String lastError;
    public long createdAt;
    public long updatedAt;
    public long queuedAt;
    public long startedAt;
    public long finishedAt;
    /** 审批截止时间（epoch millis），0 表示无需审批。 */
    public long approvalDeadline;
    /** 主动取消请求；工作循环观察到该标记后终止并迁移到 CANCELLED。 */
    public boolean cancelRequested;
    /** RETRY_WAIT 状态下允许再次调度的最早时间（epoch millis）。 */
    public long nextAttemptAt;
}
