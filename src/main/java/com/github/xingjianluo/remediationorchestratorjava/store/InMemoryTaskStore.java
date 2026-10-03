package com.github.xingjianluo.remediationorchestratorjava.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.xingjianluo.remediationorchestratorjava.model.RemediationTask;
import com.github.xingjianluo.remediationorchestratorjava.model.TaskState;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

/** 进程内任务存储，供单元测试使用（不落盘）。同样返回深拷贝以模拟持久化语义。 */
public class InMemoryTaskStore implements TaskStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, RemediationTask> tasks = new LinkedHashMap<>();

    @Override
    public synchronized RemediationTask save(RemediationTask task) {
        tasks.put(task.id, copy(task));
        return task;
    }

    @Override
    public synchronized Optional<RemediationTask> update(String taskId, UnaryOperator<RemediationTask> mutator) {
        RemediationTask current = tasks.get(taskId);
        if (current == null) {
            return Optional.empty();
        }
        RemediationTask updated = mutator.apply(copy(current));
        tasks.put(updated.id, copy(updated));
        return Optional.of(copy(updated));
    }

    @Override
    public synchronized Optional<RemediationTask> findById(String taskId) {
        return Optional.ofNullable(tasks.get(taskId)).map(InMemoryTaskStore::copy);
    }

    @Override
    public synchronized Optional<RemediationTask> findOpenByFingerprint(String fingerprint) {
        return tasks.values().stream()
                .filter(t -> fingerprint.equals(t.fingerprint))
                .filter(t -> !t.state.isTerminal())
                .min(Comparator.comparingLong(t -> t.createdAt))
                .map(InMemoryTaskStore::copy);
    }

    @Override
    public synchronized List<RemediationTask> findByFingerprint(String fingerprint) {
        return tasks.values().stream()
                .filter(t -> fingerprint.equals(t.fingerprint))
                .map(InMemoryTaskStore::copy).toList();
    }

    @Override
    public synchronized List<RemediationTask> findAll() {
        return tasks.values().stream().map(InMemoryTaskStore::copy).toList();
    }

    @Override
    public synchronized List<RemediationTask> findByTarget(String target) {
        return tasks.values().stream().filter(t -> target.equals(t.target)).map(InMemoryTaskStore::copy).toList();
    }

    @Override
    public synchronized List<RemediationTask> findByState(TaskState state) {
        return tasks.values().stream().filter(t -> t.state == state).map(InMemoryTaskStore::copy).toList();
    }

    @Override
    public synchronized List<RemediationTask> findNonTerminal() {
        return tasks.values().stream().filter(t -> !t.state.isTerminal()).map(InMemoryTaskStore::copy).toList();
    }

    private static RemediationTask copy(RemediationTask task) {
        try {
            return MAPPER.readValue(MAPPER.writeValueAsString(task), RemediationTask.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
