package com.github.xingjianluo.remediationorchestratorjava.config;

/**
 * 维护窗口。targetPattern 为具体目标或 "*"（对所有目标生效）。
 * 时间均为 epoch millis，采用左闭右开区间。
 */
public record MaintenanceWindow(String id, String targetPattern, long startMillis,
                                long endMillis, String reason) {

    public boolean covers(String target, long nowMillis) {
        boolean targetMatch = "*".equals(targetPattern) || targetPattern.equals(target);
        return targetMatch && nowMillis >= startMillis && nowMillis < endMillis;
    }
}
