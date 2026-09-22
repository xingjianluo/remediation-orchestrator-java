package com.github.xingjianluo.remediationorchestratorjava.store;

import com.github.xingjianluo.remediationorchestratorjava.model.AuditEvent;

import java.util.List;

/** 只追加的审计存储（JSON Lines）。 */
public interface AuditStore {

    void append(AuditEvent event);

    List<AuditEvent> findAll();

    List<AuditEvent> findByTaskId(String taskId);
}
