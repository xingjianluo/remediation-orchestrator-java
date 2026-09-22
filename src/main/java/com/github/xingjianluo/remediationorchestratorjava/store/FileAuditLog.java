package com.github.xingjianluo.remediationorchestratorjava.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.xingjianluo.remediationorchestratorjava.model.AuditEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/** JSONL 本地文件审计日志：每行一个事件，只追加；启动时回读到内存便于查询。 */
public class FileAuditLog implements AuditLog {

    private static final Logger log = LoggerFactory.getLogger(FileAuditLog.class);

    private final Path file;
    private final ObjectMapper mapper;
    private final List<AuditEvent> events = new ArrayList<>();

    public FileAuditLog(Path file, ObjectMapper mapper) {
        this.file = file;
        this.mapper = mapper;
    }

    public void init() {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            if (Files.exists(file)) {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    if (!line.isBlank()) {
                        events.add(mapper.readValue(line, AuditEvent.class));
                    }
                }
            }
            log.info("[audit] initialised {} events from {}", events.size(), file.toAbsolutePath());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialise FileAuditLog at " + file, e);
        }
    }

    @Override
    public synchronized void append(AuditEvent event) {
        events.add(event);
        try {
            String line = mapper.writeValueAsString(event);
            Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to append audit event", e);
        }
    }

    @Override
    public synchronized List<AuditEvent> findAll() {
        return List.copyOf(events);
    }

    @Override
    public synchronized List<AuditEvent> findByTaskId(String taskId) {
        return events.stream().filter(e -> taskId.equals(e.taskId())).toList();
    }
}
