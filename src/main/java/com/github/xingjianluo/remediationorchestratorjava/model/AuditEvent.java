package com.github.xingjianluo.remediationorchestratorjava.model;

import java.time.Instant;
import java.util.Map;

/**
 * 只追加的审计事件（不可变）。seq 在整个审计流内单调递增。
 */
public record AuditEvent(long seq, Instant timestamp, AuditType type, String taskId,
                         String alertId, String reason, Map<String, String> detail) {
    public AuditEvent {
        detail = detail == null ? Map.of() : Map.copyOf(detail);
    }
}
