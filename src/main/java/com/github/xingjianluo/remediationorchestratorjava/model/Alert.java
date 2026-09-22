package com.github.xingjianluo.remediationorchestratorjava.model;

import java.util.Objects;

/**
 * 入站告警（不可变）。fingerprintKey 不在字段中，去重键由
 * {@code DeduplicationService} 依据 target+type+规范化 content 计算。
 */
public record Alert(String alertId, String target, String type, String content) {
    public Alert {
        Objects.requireNonNull(alertId, "alertId");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(content, "content");
        if (alertId.isBlank() || target.isBlank() || type.isBlank() || content.isBlank()) {
            throw new IllegalArgumentException("alert fields must not be blank");
        }
    }
}
