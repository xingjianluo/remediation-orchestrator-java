package com.github.xingjianluo.remediationorchestratorjava.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.xingjianluo.remediationorchestratorjava.model.RemediationTask;
import com.github.xingjianluo.remediationorchestratorjava.model.TaskState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

/**
 * 基于本地文件的任务存储：每任务一个 JSON 文件。
 * 写入采用“先写临时文件再原子移动”，保证崩溃后不会出现半写文件。
 */
public class FileTaskStore implements TaskStore {

    private static final Logger log = LoggerFactory.getLogger(FileTaskStore.class);

    private final Path dir;
    private final ObjectMapper mapper;
    private final Map<String, RemediationTask> tasks = new LinkedHashMap<>();
    private final ReentrantLock lock = new ReentrantLock();

    public FileTaskStore(Path dir, ObjectMapper mapper) {
        this.dir = dir;
        this.mapper = mapper;
    }

    public void init() {
        try {
            Files.createDirectories(dir);
            try (Stream<Path> stream = Files.list(dir)) {
                List<Path> files = stream.filter(p -> p.toString().endsWith(".json"))
                        .sorted(Comparator.comparing(Path::getFileName))
                        .toList();
                for (Path file : files) {
                    RemediationTask task = mapper.readValue(file.toFile(), RemediationTask.class);
                    tasks.put(task.id, task);
                }
                log.info("[task-store] loaded {} tasks from {}", tasks.size(), dir.toAbsolutePath());
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialise FileTaskStore at " + dir, e);
        }
    }

    @Override
    public RemediationTask save(RemediationTask task) {
        lock.lock();
        try {
            tasks.put(task.id, task);
            Path target = dir.resolve(task.id + ".json");
            Path tmp = dir.resolve(task.id + ".json.tmp");
            String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(task);
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return task;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to persist task " + task.id, e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<RemediationTask> findById(String taskId) {
        lock.lock();
        try {
            return Optional.ofNullable(tasks.get(taskId)).map(this::copy);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<RemediationTask> findOpenByFingerprint(String fingerprint) {
        lock.lock();
        try {
            return tasks.values().stream()
                    .filter(t -> fingerprint.equals(t.fingerprint))
                    .filter(t -> !t.state.isTerminal())
                    .min(Comparator.comparingLong(t -> t.createdAt))
                    .map(this::copy);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<RemediationTask> findByFingerprint(String fingerprint) {
        lock.lock();
        try {
            return tasks.values().stream()
                    .filter(t -> fingerprint.equals(t.fingerprint))
                    .map(this::copy).toList();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<RemediationTask> findAll() {
        lock.lock();
        try {
            return tasks.values().stream().map(this::copy).toList();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<RemediationTask> findByTarget(String target) {
        lock.lock();
        try {
            return tasks.values().stream().filter(t -> target.equals(t.target)).map(this::copy).toList();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<RemediationTask> findByState(TaskState state) {
        lock.lock();
        try {
            return tasks.values().stream().filter(t -> t.state == state).map(this::copy).toList();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<RemediationTask> findNonTerminal() {
        lock.lock();
        try {
            return tasks.values().stream().filter(t -> !t.state.isTerminal()).map(this::copy).toList();
        } finally {
            lock.unlock();
        }
    }

    /** 反序列化一份副本返回，避免调用方绕过 save 直接改内存态。 */
    private RemediationTask copy(RemediationTask task) {
        try {
            return mapper.readValue(mapper.writeValueAsString(task), RemediationTask.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to copy task " + task.id, e);
        }
    }
}
