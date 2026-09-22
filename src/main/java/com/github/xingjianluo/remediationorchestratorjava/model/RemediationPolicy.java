package com.github.xingjianluo.remediationorchestratorjava.model;

import java.util.ArrayList;
import java.util.List;

/** 修复策略：按告警内容匹配，并声明步骤、审批与重试约束。 */
public class RemediationPolicy {
    public String id;
    public String description;
    /** 告警正文包含其中任意关键字即命中；为空列表表示通配。 */
    public List<String> matchAnyKeywords = new ArrayList<>();
    /** 最低严重级别（含），为空表示不限制。 */
    public String minSeverity;
    public boolean requiresApproval;
    /** 审批超时秒数，<=0 表示使用全局默认。 */
    public int approvalTimeoutSeconds;
    /** 步骤最大重试次数（不含首次执行），<0 表示使用全局默认。 */
    public int maxRetries = -1;
    /** 退避基数毫秒，<0 表示使用全局默认。 */
    public long backoffBaseMillis = -1;
    /** 单步骤超时毫秒，<0 表示使用全局默认。 */
    public long stepTimeoutMillis = -1;
    public boolean enabled = true;
    public List<StepSpec> steps = new ArrayList<>();
}
