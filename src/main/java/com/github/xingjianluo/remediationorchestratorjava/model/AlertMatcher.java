package com.github.xingjianluo.remediationorchestratorjava.model;

/**
 * 策略匹配条件（不可变）。matchType=KEYWORD 时 content 为以逗号分隔的关键字，
 * 告警 content 命中任一关键字即匹配；matchType=ANY 时匹配所有。
 */
public record AlertMatcher(String targetPrefix, String type, String matchType, String content) {
    public boolean matches(Alert alert) {
        if (alert == null) {
            return false;
        }
        if (targetPrefix != null && !targetPrefix.isBlank()
                && !alert.target().startsWith(targetPrefix)) {
            return false;
        }
        if (type != null && !type.isBlank() && !type.equals(alert.type())) {
            return false;
        }
        if (matchType == null || matchType.isBlank() || "ANY".equalsIgnoreCase(matchType)) {
            return true;
        }
        if ("KEYWORD".equalsIgnoreCase(matchType)) {
            if (content == null || content.isBlank()) {
                return false;
            }
            String haystack = alert.content() == null ? "" : alert.content().toLowerCase();
            for (String kw : content.split(",")) {
                String k = kw.trim().toLowerCase();
                if (!k.isEmpty() && haystack.contains(k)) {
                    return true;
                }
            }
            return false;
        }
        return false;
    }
}
