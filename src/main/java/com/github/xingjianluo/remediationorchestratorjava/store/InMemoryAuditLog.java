package com.github.xingjianluo.remediationorchestratorjava.store;

import com.github.xingjianluo.remediationorchestratorjava.model.AuditEvent;

import java.util.ArrayList;
import java.util.List;

/** 进程内审计日志，供单元测试使用。 */
public class InMemoryAuditLog implements AuditLog {

    private final List<AuditEvent> events = new ArrayList<>();

    @Override
    public synchronized void append(AuditEvent event) {
        events.add(event);
    }

    @Override
    public synchronized List<AuditEvent> findAll() {
        return List.copyOf(events);
    }

    @Override
    public synchronized List<AuditEvent> findByTaskId(String taskId) {
        return events.stream().filter(e -> taskId.equals(e.taskId())).toList();
    }
}
