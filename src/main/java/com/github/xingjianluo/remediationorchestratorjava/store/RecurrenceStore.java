package com.github.xingjianluo.remediationorchestratorjava.store;

import com.github.xingjianluo.remediationorchestratorjava.model.TargetRecurrence;

import java.util.List;
import java.util.Optional;

/** 复发跟踪状态的持久化存储（按目标）。 */
public interface RecurrenceStore {

    TargetRecurrence getOrCreate(String target);

    TargetRecurrence save(TargetRecurrence record);

    Optional<TargetRecurrence> find(String target);

    List<TargetRecurrence> findAll();
}
