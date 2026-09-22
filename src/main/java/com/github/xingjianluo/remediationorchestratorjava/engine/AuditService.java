package com.github.xingjianluo.remediationorchestratorjava.engine;

import com.github.xingjianluo.remediationorchestratorjava.model.AuditEvent;
import com.github.xingjianluo.remediationorchestratorjava.model.AuditType;
import com.github.xingjianluo.remediationorchestratorjava.store.AuditStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 审计门面：统一分配单调递增 seq，所有关键判定与状态迁移都经此记录到只追加审计流，
 * 并同步打印 [AUDIT] 日志，保证最终结论可追溯。
 */
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditStore store;
    private final Clock clock;
    private final AtomicLong seqGen = new AtomicLong(0);

    public AuditService(AuditStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
        long max = -1;
        for (AuditEvent e : store.findAll()) {
            max = Math.max(max, e.seq());
        }
        seqGen.set(max + 1);
    }

    public AuditEvent record(AuditType type, String taskId, String alertId,
                             String reason, Map<String, String> detail) {
        AuditEvent event = new AuditEvent(seqGen.getAndIncrement(), Instant.now(clock),
                type, taskId, alertId, reason, detail);
        store.append(event);
        log.info("[AUDIT] seq={} type={} task={} alert={} reason={} detail={}",
                event.seq(), type, taskId, alertId, reason, detail);
        return event;
    }

    public AuditEvent record(AuditType type, String taskId, String alertId, String reason) {
        return record(type, taskId, alertId, reason, Map.of());
    }

    public List<AuditEvent> all() {
        return store.findAll();
    }

    public List<AuditEvent> byTask(String taskId) {
        return store.findByTaskId(taskId);
    }
}
