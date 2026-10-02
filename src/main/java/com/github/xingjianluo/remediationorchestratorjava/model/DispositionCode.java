package com.github.xingjianluo.remediationorchestratorjava.model;

/** 一条告警请求进入编排器后的处理结论，被抑制/合并/拒绝的原因必须可区分。 */
public enum DispositionCode {
    CREATED,
    DUPLICATE,
    SUPPRESSED_MAINTENANCE,
    REJECTED_NO_POLICY,
    REJECTED_POLICY_DISABLED,
    /** 复发告警命中抑制窗口：不新增修复动作。 */
    RECURRENCE_SUPPRESSED,
    /** 复发告警被放行：抑制窗口已过（或升级已被人工确认），重开一次修复。 */
    RECURRENCE_REOPENED,
    /** 统计窗内复发次数达阈值：升级为需人工确认，确认前无任何修复副作用。 */
    RECURRENCE_ESCALATED,
    /** 该复发已被人工放弃：在统计窗滑出前不再自动修复。 */
    RECURRENCE_ABANDONED
}
