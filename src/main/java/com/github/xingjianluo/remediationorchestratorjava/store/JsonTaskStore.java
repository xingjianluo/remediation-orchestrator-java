package com.github.xingjianluo.remediationorchestratorjava.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.xingjianluo.remediationorchestratorjava.model.RemediationTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 基于本地 JSON 文件的任务存储：每个任务一个快照文件。
 * 写入采用“临时文件 + 原子 move”，进程在任何时刻崩溃都不会留下半写入快照。
 */
public class JsonTaskStore implements TaskStore {

    private static final Logger log = LoggerFactory.getLogger(JsonTaskStore.class);

    private final Path dir;
    private final ObjectMapper mapper;

    public JsonTaskStore(Path dataDir, ObjectMapper mapper) {
        this.dir = dataDir.resolve("tasks");
        this.mapper = mapper;
        try {
            Files.createDirectories(this.dir);
        } catch (IOException e) {
            throw new IllegalStateException("cannot create task store dir " + this.dir, e);
        }
    }

    private Path fileOf(String id) {
        return dir.resolve(id + ".json");
    }

    @Override
    public synchronized void save(RemediationTask incoming) {
        Path target = fileOf(incoming.getId());
        // 与磁盘最新快照做运行态字段合并，避免执行线程的旧内存对象覆盖并发写入
        // （如受理线程原子累加的重复告警计数）
        RemediationTask toWrite;
        if (Files.exists(target)) {
            RemediationTask current = read(target);
            current.mergeRuntimeFrom(incoming);
            toWrite = current;
        } else {
            toWrite = incoming;
        }
        Path tmp = dir.resolve(incoming.getId() + ".json.tmp");
        try {
            byte[] bytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(toWrite);
            Files.write(tmp, bytes);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicNotSupported) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("failed to persist task " + incoming.getId(), e);
        }
    }

    @Override
    public synchronized Optional<RemediationTask> findById(String id) {
        Path f = fileOf(id);
        if (!Files.exists(f)) {
            return Optional.empty();
        }
        return Optional.of(read(f));
    }

    @Override
    public synchronized Optional<RemediationTask> findActiveByFingerprint(String fingerprintKey) {
        for (RemediationTask t : loadAll()) {
            if (fingerprintKey.equals(t.getFingerprintKey()) && !t.isTerminal()) {
                return Optional.of(t);
            }
        }
        return Optional.empty();
    }

    @Override
    public synchronized Optional<RemediationTask> findLatestByFingerprint(String fingerprintKey) {
        RemediationTask latest = null;
        for (RemediationTask t : loadAll()) {
            if (fingerprintKey.equals(t.getFingerprintKey())
                    && (latest == null
                        || t.getCreatedAt().isAfter(latest.getCreatedAt()))) {
                latest = t;
            }
        }
        return Optional.ofNullable(latest);
    }

    @Override
    public synchronized List<RemediationTask> findAll() {
        return loadAll();
    }

    @Override
    public synchronized int incrementMergedDuplicate(String taskId) {
        RemediationTask latest = read(fileOf(taskId));
        latest.mergeDuplicate(java.time.Instant.now());
        save(latest);
        return latest.getMergedDuplicateCount();
    }

    @Override
    public synchronized List<RemediationTask> findNonTerminal() {
        List<RemediationTask> result = new ArrayList<>();
        for (RemediationTask t : loadAll()) {
            if (!t.isTerminal()) {
                result.add(t);
            }
        }
        return result;
    }

    private List<RemediationTask> loadAll() {
        List<RemediationTask> all = new ArrayList<>();
        if (!Files.exists(dir)) {
            return all;
        }
        try (var stream = Files.list(dir)) {
            stream.filter(p -> p.toString().endsWith(".json")).forEach(p -> {
                try {
                    all.add(read(p));
                } catch (Exception e) {
                    log.warn("[STORE] skip unreadable task snapshot {}: {}", p, e.toString());
                }
            });
        } catch (IOException e) {
            throw new IllegalStateException("failed to list task store " + dir, e);
        }
        return all;
    }

    private RemediationTask read(Path f) {
        try {
            byte[] bytes = Files.readAllBytes(f);
            return mapper.readValue(bytes, RemediationTask.class);
        } catch (IOException e) {
            throw new IllegalStateException("failed to read task snapshot " + f, e);
        }
    }

    /** 供诊断/测试使用：以 UTF-8 字符串读取原始快照（不存在返回空）。 */
    public synchronized String rawJson(String id) {
        Path f = fileOf(id);
        try {
            return Files.exists(f) ? new String(Files.readAllBytes(f), StandardCharsets.UTF_8) : "";
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
