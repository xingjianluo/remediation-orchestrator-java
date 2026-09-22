package com.github.xingjianluo.remediationorchestratorjava.action.builtin;

import com.github.xingjianluo.remediationorchestratorjava.action.ActionResult;
import com.github.xingjianluo.remediationorchestratorjava.action.RemediationAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 内置无副作用动作：仅记录日志并成功，保证整个能力可在本地无外部依赖地演示与验证。 */
public class NoopAction implements RemediationAction {

    private static final Logger log = LoggerFactory.getLogger(NoopAction.class);

    private final String name;

    public NoopAction(String name) {
        this.name = name;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public ActionResult execute(Context context) {
        log.info("[ACTION] noop action '{}' executed locally for task={} target={} params={}",
                name, context.taskId(), context.target(), context.params());
        return ActionResult.ok();
    }
}
