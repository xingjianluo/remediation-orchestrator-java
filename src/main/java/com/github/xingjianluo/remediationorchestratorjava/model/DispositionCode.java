package com.github.xingjianluo.remediationorchestratorjava.model;

/** 一条告警请求进入编排器后的处理结论，被抑制/合并/拒绝的原因必须可区分。 */
public enum DispositionCode {
    CREATED,
    DUPLICATE,
    SUPPRESSED_MAINTENANCE,
    REJECTED_NO_POLICY,
    REJECTED_POLICY_DISABLED,
    /** 复发处于抑制窗口内：不新增修复动作。 */
    RECURRENCE_SUPPRESSED,
    /** 统计窗内复发次数达阈值：升级为需人工确认。 */
    RECURRENCE_ESCALATED,
    /** 前次修复失败后再次复发：需人工确认后才可重试。 */
    RECURRENCE_AFTER_FAILURE,
    /** 人工已放弃该目标的本次修复：窗口内不再自动修复。 */
    RECURRENCE_ABANDONED
}
