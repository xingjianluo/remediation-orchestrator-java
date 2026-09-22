package com.github.xingjianluo.remediationorchestratorjava.model;

import java.time.Instant;

/** 维护窗口：[start, end) 时间区间内，目标前缀匹配的告警被抑制。 */
public final class MaintenanceWindow {

    private final String id;
    private final String targetPrefix;
    private final Instant start;
    private final Instant end;

    public MaintenanceWindow(String id, String targetPrefix, Instant start, Instant end) {
        this.id = id;
        this.targetPrefix = targetPrefix;
        this.start = start;
        this.end = end;
    }

    public boolean covers(String target, Instant now) {
        return target != null && target.startsWith(targetPrefix)
                && !now.isBefore(start) && now.isBefore(end);
    }

    public String getId() { return id; }
    public String getTargetPrefix() { return targetPrefix; }
    public Instant getStart() { return start; }
    public Instant getEnd() { return end; }
}
