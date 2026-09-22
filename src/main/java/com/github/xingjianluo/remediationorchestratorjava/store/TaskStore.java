package com.github.xingjianluo.remediationorchestratorjava.store;

import com.github.xingjianluo.remediationorchestratorjava.model.RemediationTask;

import java.util.List;
import java.util.Optional;

/** 任务存储：原子快照读写，保证崩溃后不出现半写入文件。 */
public interface TaskStore {

    void save(RemediationTask task);

    Optional<RemediationTask> findById(String id);

    Optional<RemediationTask> findActiveByFingerprint(String fingerprintKey);

    /** 返回该指纹最近创建的任务（含终态），按创建时间倒序取第一条。 */
    Optional<RemediationTask> findLatestByFingerprint(String fingerprintKey);

    List<RemediationTask> findAll();

    List<RemediationTask> findNonTerminal();

    /**
     * 原子地为任务累加一次“合并的重复告警”计数并返回最新值。
     * 与执行线程的快照写入互不覆盖（存储层内做读-改-写）。
     */
    int incrementMergedDuplicate(String taskId);
}
