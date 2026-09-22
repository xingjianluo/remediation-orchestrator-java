package com.github.xingjianluo.remediationorchestratorjava.action;

/** 本地修复动作执行结果：success 为 true 时步骤成功，否则 error 记录失败原因。 */
public record ActionResult(boolean success, String error) {

    public static ActionResult ok() {
        return new ActionResult(true, null);
    }

    public static ActionResult fail(String error) {
        return new ActionResult(false, error);
    }
}
