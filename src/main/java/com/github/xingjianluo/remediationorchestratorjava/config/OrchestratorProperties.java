package com.github.xingjianluo.remediationorchestratorjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 编排器可配置项，前缀 remediation.*。 */
@ConfigurationProperties(prefix = "remediation")
public class OrchestratorProperties {

    /** 全局并发上限：同时运行的任务数超过该值时进入排队而非丢弃。 */
    private int globalConcurrency = 2;
    /** 高风险修复的人工审批超时（毫秒）。 */
    private long approvalTimeoutMs = 60_000;
    /** 本地持久化目录（任务快照 + 审计日志）。 */
    private String dataDir = "data";
    /** 启动时是否扫描存储并恢复未完成任务。 */
    private boolean recoverOnStartup = true;

    public int getGlobalConcurrency() { return globalConcurrency; }
    public void setGlobalConcurrency(int globalConcurrency) {
        this.globalConcurrency = globalConcurrency;
    }

    public long getApprovalTimeoutMs() { return approvalTimeoutMs; }
    public void setApprovalTimeoutMs(long approvalTimeoutMs) {
        this.approvalTimeoutMs = approvalTimeoutMs;
    }

    public String getDataDir() { return dataDir; }
    public void setDataDir(String dataDir) { this.dataDir = dataDir; }

    public boolean isRecoverOnStartup() { return recoverOnStartup; }
    public void setRecoverOnStartup(boolean recoverOnStartup) {
        this.recoverOnStartup = recoverOnStartup;
    }
}
