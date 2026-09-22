package com.github.xingjianluo.remediationorchestratorjava.action;

import com.github.xingjianluo.remediationorchestratorjava.model.StepSpec;
import com.github.xingjianluo.remediationorchestratorjava.model.TaskStep;

import java.util.Map;

/**
 * 本地修复动作 SPI。实现类注册为 Spring bean，bean 名称即策略步骤中的 actionName。
 * 实现必须只依赖本地资源、无外部服务，并尽量幂等（崩溃恢复后可能再次被调用判定）。
 */
public interface RemediationAction {

    String name();

    ActionResult execute(Context context) throws Exception;

    /** 动作执行上下文，携带任务与步骤信息。 */
    record Context(String taskId, String target, String alertId, String stepName,
                   Map<String, String> params) {

        public static Context of(String taskId, String target, String alertId,
                                 StepSpec spec) {
            return new Context(taskId, target, alertId, spec.name(), spec.params());
        }

        public static Context of(String taskId, String target, String alertId,
                                 TaskStep step) {
            return new Context(taskId, target, alertId, step.getName(), step.getParams());
        }
    }
}
