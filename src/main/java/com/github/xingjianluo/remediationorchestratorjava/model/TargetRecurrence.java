package com.github.xingjianluo.remediationorchestratorjava.model;

import java.util.ArrayList;
import java.util.List;

/** 同一目标的复发跟踪状态（持久化，跨重启保留）。 */
public class TargetRecurrence {
    public String target;
    /** 复发事件时间戳（epoch millis）：前次修复终结后等价告警再次到达的时刻。 */
    public List<Long> recurrenceEvents = new ArrayList<>();
    /** 人工确认时刻（epoch millis），0 表示未确认；确认被消费后复位为 0。 */
    public long confirmedAt;
    /** 人工放弃时刻（epoch millis），0 表示未放弃。 */
    public long abandonedAt;
}
