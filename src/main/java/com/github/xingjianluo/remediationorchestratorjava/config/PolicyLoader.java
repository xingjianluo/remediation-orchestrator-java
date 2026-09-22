package com.github.xingjianluo.remediationorchestratorjava.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.xingjianluo.remediationorchestratorjava.model.RemediationPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** 从 classpath 或本地文件加载策略 JSON。 */
public class PolicyLoader {

    private static final Logger log = LoggerFactory.getLogger(PolicyLoader.class);
    private static final TypeReference<List<RemediationPolicy>> TYPE = new TypeReference<>() {
    };

    private final ObjectMapper mapper = new ObjectMapper();

    public List<RemediationPolicy> load(String location) {
        try {
            List<RemediationPolicy> result;
            if (location.startsWith("classpath:")) {
                String resource = location.substring("classpath:".length());
                try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
                    if (in == null) {
                        log.warn("[policies] classpath resource not found: {}", resource);
                        return List.of();
                    }
                    result = mapper.readValue(in, TYPE);
                }
            } else {
                Path path = Path.of(location);
                if (!Files.exists(path)) {
                    log.warn("[policies] policy file not found: {}", path.toAbsolutePath());
                    return List.of();
                }
                result = mapper.readValue(path.toFile(), TYPE);
            }
            log.info("[policies] loaded {} policies from {}", result.size(), location);
            return result;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load remediation policies from " + location, e);
        }
    }
}
