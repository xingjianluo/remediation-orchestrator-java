package com.github.xingjianluo.remediationorchestratorjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 编排器可配置项，前缀 remediation.orchestrator。 */
@ConfigurationProperties(prefix = "remediation.orchestrator")
public class OrchestratorProperties {

    /** 任务状态与审计日志的持久化目录。 */
    private String dataDir = "data/remediation";
    /** 全局同时执行修复步骤的任务数上限。 */
    private int maxConcurrent = 4;
    /** 默认步骤最大重试次数（不含首次执行）。 */
    private int defaultMaxRetries = 2;
    /** 默认退避基数毫秒，第 n 次重试等待 base * n。 */
    private long defaultBackoffBaseMillis = 200;
    /** 默认单步骤超时毫秒。 */
    private long defaultStepTimeoutMillis = 5000;
    /** 默认审批超时秒数。 */
    private int defaultApprovalTimeoutSeconds = 60;
    /** 调度器扫描间隔毫秒。 */
    private long tickMillis = 50;
    /** 策略文件位置，支持 classpath: 与文件路径。 */
    private String policiesLocation = "classpath:policies.json";
    /** 维护窗口配置文件，为空时仅使用 dataDir 下的文件与运行时 API。 */
    private String maintenanceFile = "";
    /** 复发抑制窗口毫秒：上一次修复终结后该时长内的复发告警不新增修复动作。 */
    private long recurrenceSuppressionWindowMillis = 300_000;
    /** 复发统计时间窗毫秒：统计同一指纹终结修复轮次的滑动窗口。 */
    private long recurrenceStatsWindowMillis = 3_600_000;
    /** 复发升级阈值：统计窗内终结修复轮次达到该值即升级为需人工确认。 */
    private int recurrenceEscalationThreshold = 3;

    public String getDataDir() { return dataDir; }
    public void setDataDir(String dataDir) { this.dataDir = dataDir; }
    public int getMaxConcurrent() { return maxConcurrent; }
    public void setMaxConcurrent(int maxConcurrent) { this.maxConcurrent = maxConcurrent; }
    public int getDefaultMaxRetries() { return defaultMaxRetries; }
    public void setDefaultMaxRetries(int defaultMaxRetries) { this.defaultMaxRetries = defaultMaxRetries; }
    public long getDefaultBackoffBaseMillis() { return defaultBackoffBaseMillis; }
    public void setDefaultBackoffBaseMillis(long defaultBackoffBaseMillis) { this.defaultBackoffBaseMillis = defaultBackoffBaseMillis; }
    public long getDefaultStepTimeoutMillis() { return defaultStepTimeoutMillis; }
    public void setDefaultStepTimeoutMillis(long defaultStepTimeoutMillis) { this.defaultStepTimeoutMillis = defaultStepTimeoutMillis; }
    public int getDefaultApprovalTimeoutSeconds() { return defaultApprovalTimeoutSeconds; }
    public void setDefaultApprovalTimeoutSeconds(int defaultApprovalTimeoutSeconds) { this.defaultApprovalTimeoutSeconds = defaultApprovalTimeoutSeconds; }
    public long getTickMillis() { return tickMillis; }
    public void setTickMillis(long tickMillis) { this.tickMillis = tickMillis; }
    public String getPoliciesLocation() { return policiesLocation; }
    public void setPoliciesLocation(String policiesLocation) { this.policiesLocation = policiesLocation; }
    public String getMaintenanceFile() { return maintenanceFile; }
    public void setMaintenanceFile(String maintenanceFile) { this.maintenanceFile = maintenanceFile; }
    public long getRecurrenceSuppressionWindowMillis() { return recurrenceSuppressionWindowMillis; }
    public void setRecurrenceSuppressionWindowMillis(long v) { this.recurrenceSuppressionWindowMillis = v; }
    public long getRecurrenceStatsWindowMillis() { return recurrenceStatsWindowMillis; }
    public void setRecurrenceStatsWindowMillis(long v) { this.recurrenceStatsWindowMillis = v; }
    public int getRecurrenceEscalationThreshold() { return recurrenceEscalationThreshold; }
    public void setRecurrenceEscalationThreshold(int v) { this.recurrenceEscalationThreshold = v; }
}
