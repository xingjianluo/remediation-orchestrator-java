package com.github.xingjianluo.remediationorchestratorjava.recurrence;

import com.github.xingjianluo.remediationorchestratorjava.config.OrchestratorProperties;
import com.github.xingjianluo.remediationorchestratorjava.model.AuditEvent;
import com.github.xingjianluo.remediationorchestratorjava.model.AuditEvents;
import com.github.xingjianluo.remediationorchestratorjava.model.TargetRecurrence;
import com.github.xingjianluo.remediationorchestratorjava.store.AuditLog;
import com.github.xingjianluo.remediationorchestratorjava.store.RecurrenceStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 复发判定与人工确认/放弃。
 * <ul>
 *   <li>复发事件按目标记录（前次修复终结后等价告警再次到达），持久化跨重启保留；</li>
 *   <li>升级判定为派生状态：统计窗内复发次数 ≥ 阈值即升级，窗口滑动后计数自然回落、升级自动解除；</li>
 *   <li>人工确认：清除已累计事件并标记 confirmedAt，下一次复发放行一次真实修复（消费后复位）；</li>
 *   <li>人工放弃：清除已累计事件并标记 abandonedAt，统计窗内复发一律不再自动修复，窗口滑过后自然解除。</li>
 * </ul>
 */
public class RecurrenceService {

    private static final Logger log = LoggerFactory.getLogger(RecurrenceService.class);

    private final RecurrenceStore store;
    private final AuditLog auditLog;
    private final OrchestratorProperties props;
    private final Clock clock;

    public RecurrenceService(RecurrenceStore store, AuditLog auditLog,
                             OrchestratorProperties props, Clock clock) {
        this.store = store;
        this.auditLog = auditLog;
        this.props = props;
        this.clock = clock;
    }

    /** 记录一次复发事件并返回统计窗内的当前复发次数。 */
    public int recordRecurrence(String target) {
        long now = clock.millis();
        TargetRecurrence record = store.getOrCreate(target);
        record.recurrenceEvents.add(now);
        prune(record, now);
        store.save(record);
        int count = record.recurrenceEvents.size();
        log.info("[recurrence] recorded target={} count={}/{} windowMillis={}",
                target, count, props.getRecurrenceEscalationThreshold(), props.getRecurrenceStatsWindowMillis());
        return count;
    }

    /** 统计窗内的复发次数（只读，不落库）。 */
    public int countWithinWindow(String target) {
        long now = clock.millis();
        return store.find(target)
                .map(r -> (int) r.recurrenceEvents.stream().filter(t -> t >= now - window()).count())
                .orElse(0);
    }

    /** 是否已升级（统计窗内复发次数达阈值）。派生状态，随窗口滑动自然解除。 */
    public boolean isEscalated(String target) {
        return countWithinWindow(target) >= props.getRecurrenceEscalationThreshold();
    }

    /** 是否处于人工放弃期（放弃后一个统计窗内）。 */
    public boolean isAbandoned(String target) {
        long now = clock.millis();
        return store.find(target)
                .map(r -> r.abandonedAt > 0 && now - r.abandonedAt < window())
                .orElse(false);
    }

    /** 是否存在待消费的人工确认。 */
    public boolean hasConfirmation(String target) {
        return store.find(target).map(r -> r.confirmedAt > 0).orElse(false);
    }

    /** 消费一次人工确认（放行一次真实修复后复位）。 */
    public void consumeConfirmation(String target) {
        TargetRecurrence record = store.getOrCreate(target);
        record.confirmedAt = 0;
        store.save(record);
        log.info("[recurrence] confirmation consumed target={}", target);
    }

    /** 人工确认：清除已累计复发事件，允许下一次复发放行一次真实修复。 */
    public void confirm(String target, String operator) {
        long now = clock.millis();
        TargetRecurrence record = store.getOrCreate(target);
        int cleared = record.recurrenceEvents.size();
        record.recurrenceEvents.clear();
        record.confirmedAt = now;
        record.abandonedAt = 0;
        store.save(record);
        auditLog.append(new AuditEvent(now, AuditEvents.RECURRENCE_CONFIRMED, null, null, Map.of(
                "target", target,
                "operator", operator == null ? "" : operator,
                "clearedEvents", cleared)));
        log.info("[recurrence] confirmed target={} operator={} clearedEvents={}", target, operator, cleared);
    }

    /** 人工放弃：清除已累计复发事件，统计窗内复发不再自动修复。 */
    public void abandon(String target, String operator, String reason) {
        long now = clock.millis();
        TargetRecurrence record = store.getOrCreate(target);
        int cleared = record.recurrenceEvents.size();
        record.recurrenceEvents.clear();
        record.abandonedAt = now;
        record.confirmedAt = 0;
        store.save(record);
        auditLog.append(new AuditEvent(now, AuditEvents.RECURRENCE_ABANDON, null, null, Map.of(
                "target", target,
                "operator", operator == null ? "" : operator,
                "reason", reason == null ? "" : reason,
                "clearedEvents", cleared)));
        log.info("[recurrence] abandoned target={} operator={} reason={}", target, operator, reason);
    }

    /** 单个目标的复发/升级状态视图。 */
    public RecurrenceStatus status(String target) {
        long now = clock.millis();
        TargetRecurrence record = store.find(target).orElse(null);
        List<Long> events = record == null ? List.of()
                : record.recurrenceEvents.stream().filter(t -> t >= now - window()).sorted().toList();
        return new RecurrenceStatus(
                target,
                events.size(),
                events.size() >= props.getRecurrenceEscalationThreshold(),
                record != null && record.confirmedAt > 0,
                record != null && record.abandonedAt > 0 && now - record.abandonedAt < window(),
                props.getRecurrenceSuppressionWindowMillis(),
                props.getRecurrenceStatsWindowMillis(),
                props.getRecurrenceEscalationThreshold(),
                events);
    }

    /** 全部有复发记录的目标状态视图。 */
    public List<RecurrenceStatus> allStatus() {
        List<RecurrenceStatus> all = new ArrayList<>();
        for (TargetRecurrence record : store.findAll()) {
            all.add(status(record.target));
        }
        return all;
    }

    private long window() {
        return props.getRecurrenceStatsWindowMillis();
    }

    private void prune(TargetRecurrence record, long now) {
        record.recurrenceEvents.removeIf(t -> t < now - window());
    }
}
