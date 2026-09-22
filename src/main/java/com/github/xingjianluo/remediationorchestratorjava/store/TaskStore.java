package com.github.xingjianluo.remediationorchestratorjava.store;

import com.github.xingjianluo.remediationorchestratorjava.model.RemediationTask;
import com.github.xingjianluo.remediationorchestratorjava.model.TaskState;

import java.util.List;
import java.util.Optional;

/** 修复任务的持久化存储。 */
public interface TaskStore {

    RemediationTask save(RemediationTask task);

    Optional<RemediationTask> findById(String taskId);

    /** 按去重指纹查找未终结任务（重复告警判定）。 */
    Optional<RemediationTask> findOpenByFingerprint(String fingerprint);

    List<RemediationTask> findAll();

    List<RemediationTask> findByTarget(String target);

    List<RemediationTask> findByState(TaskState state);

    /** 返回处于所有非终结状态的任务（用于重启恢复）。 */
    List<RemediationTask> findNonTerminal();
}
