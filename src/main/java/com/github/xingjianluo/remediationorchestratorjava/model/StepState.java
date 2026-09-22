package com.github.xingjianluo.remediationorchestratorjava.model;

/** 单个修复步骤的状态。 */
public enum StepState {
    PENDING, RUNNING, SUCCEEDED, FAILED, SKIPPED, CANCELLED
}
