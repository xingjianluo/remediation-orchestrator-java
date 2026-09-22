package com.github.xingjianluo.remediationorchestratorjava.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.xingjianluo.remediationorchestratorjava.model.AuditEvent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * JSON Lines 审计存储：每条事件一行，只追加。启动时把历史读入内存以便查询。
 */
public class JsonlAuditStore implements AuditStore {

    private final Path file;
    private final ObjectMapper mapper;
    private final List<AuditEvent> events = new ArrayList<>();

    public JsonlAuditStore(Path dataDir, ObjectMapper mapper) {
        this.file = dataDir.resolve("audit.log");
        this.mapper = mapper;
        try {
            Files.createDirectories(dataDir);
            if (Files.exists(file)) {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    if (!line.isBlank()) {
                        events.add(mapper.readValue(line, AuditEvent.class));
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot init audit store " + file, e);
        }
    }

    @Override
    public synchronized void append(AuditEvent event) {
        try {
            String line = mapper.writeValueAsString(event);
            Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            events.add(event);
        } catch (IOException e) {
            throw new IllegalStateException("failed to append audit event", e);
        }
    }

    @Override
    public synchronized List<AuditEvent> findAll() {
        return List.copyOf(events);
    }

    @Override
    public synchronized List<AuditEvent> findByTaskId(String taskId) {
        List<AuditEvent> result = new ArrayList<>();
        for (AuditEvent e : events) {
            if (taskId != null && taskId.equals(e.taskId())) {
                result.add(e);
            }
        }
        return result;
    }
}
