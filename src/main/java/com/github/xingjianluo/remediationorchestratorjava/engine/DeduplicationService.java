package com.github.xingjianluo.remediationorchestratorjava.engine;

import com.github.xingjianluo.remediationorchestratorjava.model.Alert;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 去重键计算：SHA-256(target | type | 规范化 content)。
 * content 规范化：trim + 连续空白折叠为单空格，避免等价告警因空白差异重复触发。
 */
public class DeduplicationService {

    public String fingerprint(Alert alert) {
        String normalized = normalize(alert.content());
        String raw = alert.target() + "|" + alert.type() + "|" + normalized;
        return sha256(raw);
    }

    private String normalize(String content) {
        return content.trim().replaceAll("\\s+", " ");
    }

    private String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
