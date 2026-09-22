package com.github.xingjianluo.remediationorchestratorjava.executor;

import com.github.xingjianluo.remediationorchestratorjava.model.RemediationTask;
import com.github.xingjianluo.remediationorchestratorjava.model.TaskStep;

/** 一个本地修复动作类型的处理器；实现必须响应线程中断。 */
public interface StepHandler {

    com.github.xingjianluo.remediationorchestratorjava.model.StepType type();

    StepExecutionResult execute(TaskStep step, RemediationTask task) throws InterruptedException;
}
