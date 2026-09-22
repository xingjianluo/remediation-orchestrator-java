package com.github.xingjianluo.remediationorchestratorjava.model;

/**
 * 一条进入编排器的告警。
 *
 * @param id         告警唯一 ID
 * @param target     修复目标（同一目标上的任务串行执行）
 * @param content    告警正文，用于关键字匹配策略
 * @param severity   严重级别 INFO/WARN/CRITICAL
 * @param occurredAt 发生时间（epoch millis）
 */
public record Alert(String id, String target, String content, String severity, long occurredAt) {
}
