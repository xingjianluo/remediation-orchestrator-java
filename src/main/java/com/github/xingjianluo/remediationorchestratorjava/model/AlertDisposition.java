package com.github.xingjianluo.remediationorchestratorjava.model;

/**
 * 告警受理结论（不可变）。reason 为可区分原因；taskId 仅在接受/合并时存在，
 * windowId 仅在维护窗口抑制时存在。
 */
public record AlertDisposition(DispositionReason reason, String taskId, String windowId) {

    public static AlertDisposition accepted(String taskId) {
        return new AlertDisposition(DispositionReason.ACCEPTED, taskId, null);
    }

    public static AlertDisposition duplicate(String taskId, DispositionReason reason) {
        return new AlertDisposition(reason, taskId, null);
    }

    public static AlertDisposition suppressed(String windowId) {
        return new AlertDisposition(DispositionReason.SUPPRESSED_MAINTENANCE, null, windowId);
    }

    public static AlertDisposition rejected(DispositionReason reason) {
        return new AlertDisposition(reason, null, null);
    }
}
