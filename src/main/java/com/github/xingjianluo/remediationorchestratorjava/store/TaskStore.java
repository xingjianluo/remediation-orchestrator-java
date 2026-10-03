package com.github.xingjianluo.remediationorchestratorjava.store;

import com.github.xingjianluo.remediationorchestratorjava.model.RemediationTask;
import com.github.xingjianluo.remediationorchestratorjava.model.TaskState;

import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

/** 修复任务的持久化存储。 */
public interface TaskStore {

    RemediationTask save(RemediationTask task);

    /**
     * 原子读-改-写：在存储锁内对指定任务应用 mutator 并持久化，返回更新后的副本；
     * 任务不存在时返回空且不调用 mutator。
     * 用于关停/取消等并发路径：判定（是否已取消、是否已终结）与写回在同一把锁内完成，
     * 避免“先读再写”之间被另一方覆盖，保证并发结论唯一、可判定。
     */
    Optional<RemediationTask> update(String taskId, UnaryOperator<RemediationTask> mutator);

    Optional<RemediationTask> findById(String taskId);

    /** 按去重指纹查找未终结任务（重复告警判定）。 */
    Optional<RemediationTask> findOpenByFingerprint(String fingerprint);

    /** 按去重指纹查找全部任务（含终态，用于复发判定）。 */
    List<RemediationTask> findByFingerprint(String fingerprint);

    List<RemediationTask> findAll();

    List<RemediationTask> findByTarget(String target);

    List<RemediationTask> findByState(TaskState state);

    /** 返回处于所有非终结状态的任务（用于重启恢复）。 */
    List<RemediationTask> findNonTerminal();
}
