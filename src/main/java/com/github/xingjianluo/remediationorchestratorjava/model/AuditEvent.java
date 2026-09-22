package com.github.xingjianluo.remediationorchestratorjava.model;

import java.util.Map;

/**
 * 审计事件：状态机的每次关键迁移都追加一条，只增不改。
 *
 * @param timestamp epoch millis
 * @param event     事件类型（见 AuditEvents 常量）
 * @param taskId    关联任务（可空）
 * @param alertId   关联告警（可空）
 * @param detail    判定依据与关键数据
 */
public record AuditEvent(long timestamp, String event, String taskId, String alertId,
                         Map<String, Object> detail) {
}
