package com.github.xingjianluo.remediationorchestratorjava.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.xingjianluo.remediationorchestratorjava.model.TargetRecurrence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 基于本地文件的复发存储：全部目标的复发状态放在单个 JSON 文件中。
 * 写入采用“先写临时文件再原子移动”，保证崩溃后不会出现半写文件；
 * 重启后复发计数、确认/放弃标记不丢失。
 */
public class FileRecurrenceStore implements RecurrenceStore {

    private static final Logger log = LoggerFactory.getLogger(FileRecurrenceStore.class);

    private final Path file;
    private final ObjectMapper mapper;
    private final Map<String, TargetRecurrence> records = new LinkedHashMap<>();
    private final ReentrantLock lock = new ReentrantLock();

    public FileRecurrenceStore(Path file, ObjectMapper mapper) {
        this.file = file;
        this.mapper = mapper;
    }

    public void init() {
        lock.lock();
        try {
            if (!Files.exists(file)) {
                log.info("[recurrence-store] no existing file {}, starting empty", file.toAbsolutePath());
                return;
            }
            RecurrenceFileData data = mapper.readValue(file.toFile(), RecurrenceFileData.class);
            if (data.records != null) {
                for (TargetRecurrence r : data.records) {
                    records.put(r.target, r);
                }
            }
            log.info("[recurrence-store] loaded {} target records from {}",
                    records.size(), file.toAbsolutePath());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialise FileRecurrenceStore at " + file, e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public TargetRecurrence getOrCreate(String target) {
        lock.lock();
        try {
            TargetRecurrence existing = records.get(target);
            if (existing != null) {
                return copy(existing);
            }
            TargetRecurrence created = new TargetRecurrence();
            created.target = target;
            records.put(target, created);
            persistLocked();
            return copy(created);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public TargetRecurrence save(TargetRecurrence record) {
        lock.lock();
        try {
            records.put(record.target, copy(record));
            persistLocked();
            return record;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<TargetRecurrence> find(String target) {
        lock.lock();
        try {
            return Optional.ofNullable(records.get(target)).map(this::copy);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<TargetRecurrence> findAll() {
        lock.lock();
        try {
            return records.values().stream().map(this::copy).toList();
        } finally {
            lock.unlock();
        }
    }

    private void persistLocked() {
        try {
            Files.createDirectories(file.getParent());
            RecurrenceFileData data = new RecurrenceFileData();
            data.records = new ArrayList<>(records.values());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(data),
                    StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to persist recurrence records to " + file, e);
        }
    }

    private TargetRecurrence copy(TargetRecurrence record) {
        try {
            return mapper.readValue(mapper.writeValueAsString(record), TargetRecurrence.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to copy recurrence record " + record.target, e);
        }
    }

    /** 磁盘文件结构。 */
    public static class RecurrenceFileData {
        public List<TargetRecurrence> records = new ArrayList<>();
    }
}
