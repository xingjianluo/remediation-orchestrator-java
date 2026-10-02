package com.github.xingjianluo.remediationorchestratorjava.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.xingjianluo.remediationorchestratorjava.model.EscalationDecision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 升级人工决定的持久化存储（按告警指纹一条记录）。
 * 构造时传入文件路径则落盘（先写临时文件再原子移动），传 null 则仅内存（测试用）。
 */
public class EscalationStore {

    private static final Logger log = LoggerFactory.getLogger(EscalationStore.class);

    private final Path file;
    private final ObjectMapper mapper;
    private final Map<String, EscalationDecision> decisions = new LinkedHashMap<>();

    public EscalationStore(Path file, ObjectMapper mapper) {
        this.file = file;
        this.mapper = mapper;
    }

    /** 进程内模式（不落盘）。 */
    public static EscalationStore inMemory() {
        return new EscalationStore(null, new ObjectMapper());
    }

    public synchronized void init() {
        if (file == null) {
            return;
        }
        try {
            if (Files.exists(file)) {
                EscalationDecision[] loaded = mapper.readValue(file.toFile(), EscalationDecision[].class);
                for (EscalationDecision d : loaded) {
                    decisions.put(d.fingerprint, d);
                }
            }
            log.info("[escalation-store] loaded {} decisions from {}", decisions.size(), file);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialise EscalationStore at " + file, e);
        }
    }

    public synchronized Optional<EscalationDecision> find(String fingerprint) {
        return Optional.ofNullable(decisions.get(fingerprint));
    }

    public synchronized EscalationDecision save(EscalationDecision decision) {
        decisions.put(decision.fingerprint, decision);
        persist();
        return decision;
    }

    public synchronized Map<String, EscalationDecision> all() {
        return Map.copyOf(decisions);
    }

    private void persist() {
        if (file == null) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            String json = mapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(decisions.values());
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to persist escalation decisions to " + file, e);
        }
    }
}
