package com.github.xingjianluo.remediationorchestratorjava.executor;

/** 步骤一次尝试的执行结果。 */
public record StepExecutionResult(boolean success, String error) {
    public static StepExecutionResult ok() {
        return new StepExecutionResult(true, null);
    }

    public static StepExecutionResult fail(String error) {
        return new StepExecutionResult(false, error);
    }
}
