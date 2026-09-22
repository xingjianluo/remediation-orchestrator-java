package com.github.xingjianluo.remediationorchestratorjava.model;

/** 一条告警请求进入编排器后的处理结论，被抑制/合并/拒绝的原因必须可区分。 */
public enum DispositionCode {
    CREATED,
    DUPLICATE,
    SUPPRESSED_MAINTENANCE,
    REJECTED_NO_POLICY,
    REJECTED_POLICY_DISABLED
}
