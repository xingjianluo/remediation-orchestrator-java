package com.github.xingjianluo.remediationorchestratorjava.model;

/**
 * 告警受理结论。
 *
 * @param disposition 处理结论
 * @param taskId      关联任务 ID（CREATED 时为新任务，DUPLICATE 时为已有任务）
 * @param reason      判定依据说明
 */
public record AcceptResult(DispositionCode disposition, String taskId, String reason) {
}
