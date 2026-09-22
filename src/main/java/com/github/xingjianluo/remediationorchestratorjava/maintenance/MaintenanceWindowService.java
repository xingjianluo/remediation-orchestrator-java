package com.github.xingjianluo.remediationorchestratorjava.maintenance;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.xingjianluo.remediationorchestratorjava.config.MaintenanceWindow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 维护窗口判定与管理。
 * 窗口数据保存在内存列表中，并可落盘到本地 JSON（先写临时文件再原子替换）。
 */
public class MaintenanceWindowService {

    private static final Logger log = LoggerFactory.getLogger(MaintenanceWindowService.class);
    private static final TypeReference<List<MaintenanceWindow>> TYPE = new TypeReference<>() {
    };

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<MaintenanceWindow> windows = new ArrayList<>();
    private Path persistedFile;

    public MaintenanceWindowService() {
    }

    /** 从本地 JSON 文件加载窗口；文件不存在则空列表启动。 */
    public synchronized void loadFromFile(Path path) {
        this.persistedFile = path;
        try {
            if (path != null && Files.exists(path)) {
                List<MaintenanceWindow> loaded = mapper.readValue(path.toFile(), TYPE);
                windows.clear();
                windows.addAll(loaded);
                log.info("[maintenance] loaded {} windows from {}", windows.size(), path.toAbsolutePath());
            } else {
                log.info("[maintenance] no window file at {}, starting empty",
                        path == null ? null : path.toAbsolutePath());
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load maintenance windows from " + path, e);
        }
    }

    public synchronized boolean isSuppressed(String target, long nowMillis) {
        return !activeWindows(target, nowMillis).isEmpty();
    }

    public synchronized List<MaintenanceWindow> activeWindows(String target, long nowMillis) {
        List<MaintenanceWindow> hit = windows.stream()
                .filter(w -> w.covers(target, nowMillis))
                .sorted(Comparator.comparingLong(MaintenanceWindow::startMillis))
                .toList();
        if (!hit.isEmpty()) {
            log.info("[maintenance] target={} suppressed by windows {} at {}",
                    target, hit.stream().map(MaintenanceWindow::id).toList(), nowMillis);
        }
        return hit;
    }

    public synchronized MaintenanceWindow addWindow(String targetPattern, long startMillis,
                                                    long endMillis, String reason) {
        if (!"*".equals(targetPattern) && (targetPattern == null || targetPattern.isBlank())) {
            throw new IllegalArgumentException("targetPattern must not be blank (or use '*')");
        }
        if (endMillis <= startMillis) {
            throw new IllegalArgumentException("endMillis must be greater than startMillis");
        }
        MaintenanceWindow window = new MaintenanceWindow(
                "mw-" + UUID.randomUUID().toString().substring(0, 8),
                targetPattern, startMillis, endMillis, reason);
        windows.add(window);
        persist();
        log.info("[maintenance] window added id={}, target={}, [{}..{}), reason={}",
                window.id(), targetPattern, startMillis, endMillis, reason);
        return window;
    }

    public synchronized List<MaintenanceWindow> listWindows() {
        return List.copyOf(windows);
    }

    public synchronized boolean removeWindow(String id) {
        boolean removed = windows.removeIf(w -> w.id().equals(id));
        if (removed) {
            persist();
            log.info("[maintenance] window removed id={}", id);
        }
        return removed;
    }

    private void persist() {
        if (persistedFile == null) {
            return;
        }
        try {
            Path parent = persistedFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = persistedFile.resolveSibling(persistedFile.getFileName() + ".tmp");
            String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(windows);
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            Files.move(tmp, persistedFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to persist maintenance windows to " + persistedFile, e);
        }
    }
}
