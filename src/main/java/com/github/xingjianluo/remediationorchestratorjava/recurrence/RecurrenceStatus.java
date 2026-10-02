package com.github.xingjianluo.remediationorchestratorjava.recurrence;

import java.util.List;

/** 某一目标的复发/升级状态视图（供查询接口返回）。 */
public record RecurrenceStatus(
        String target,
        int recurrenceCount,
        boolean escalated,
        boolean confirmed,
        boolean abandoned,
        long suppressionWindowMillis,
        long statsWindowMillis,
        int escalationThreshold,
        List<Long> recurrenceEvents) {
}
