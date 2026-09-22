package com.github.xingjianluo.remediationorchestratorjava.store;

import com.github.xingjianluo.remediationorchestratorjava.model.AuditEvent;

import java.util.List;

/** 只增不改的审计日志。 */
public interface AuditLog {

    void append(AuditEvent event);

    List<AuditEvent> findAll();

    List<AuditEvent> findByTaskId(String taskId);
}
