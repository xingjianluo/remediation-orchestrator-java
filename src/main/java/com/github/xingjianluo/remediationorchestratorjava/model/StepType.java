package com.github.xingjianluo.remediationorchestratorjava.model;

/** 仅使用本地资源的修复动作类型。 */
public enum StepType {
    MARKER,      // 向本地文件追加一行标记，证明副作用发生
    NOOP,        // 空操作，恒成功
    FLAKY,       // 前 n 次尝试失败、之后成功，用于验证重试
    ALWAYS_FAIL, // 恒失败，用于验证失败终态与信息留存
    SLOW         // 睡眠指定毫秒，用于验证步骤超时
}
