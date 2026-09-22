package com.github.xingjianluo.remediationorchestratorjava.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.xingjianluo.remediationorchestratorjava.action.RemediationAction;
import com.github.xingjianluo.remediationorchestratorjava.action.builtin.NoopAction;
import com.github.xingjianluo.remediationorchestratorjava.engine.AlertIntakeService;
import com.github.xingjianluo.remediationorchestratorjava.engine.ApprovalService;
import com.github.xingjianluo.remediationorchestratorjava.engine.AuditService;
import com.github.xingjianluo.remediationorchestratorjava.engine.DeduplicationService;
import com.github.xingjianluo.remediationorchestratorjava.engine.MaintenanceWindowService;
import com.github.xingjianluo.remediationorchestratorjava.engine.RecoveryService;
import com.github.xingjianluo.remediationorchestratorjava.engine.StrategyMatcher;
import com.github.xingjianluo.remediationorchestratorjava.engine.TaskDispatcher;
import com.github.xingjianluo.remediationorchestratorjava.engine.TaskExecutor;
import com.github.xingjianluo.remediationorchestratorjava.model.AlertMatcher;
import com.github.xingjianluo.remediationorchestratorjava.model.RemediationStrategy;
import com.github.xingjianluo.remediationorchestratorjava.model.RiskLevel;
import com.github.xingjianluo.remediationorchestratorjava.model.StepSpec;
import com.github.xingjianluo.remediationorchestratorjava.store.AuditStore;
import com.github.xingjianluo.remediationorchestratorjava.store.JsonTaskStore;
import com.github.xingjianluo.remediationorchestratorjava.store.JsonlAuditStore;
import com.github.xingjianluo.remediationorchestratorjava.store.TaskStore;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;

/**
 * 编排器装配：仅依赖本地文件与容器内 bean，无任何外部服务。
 * 内置两条演示策略：
 * <ul>
 *   <li>disk-full（低风险，自动执行，两步，演示串行步骤）；</li>
 *   <li>service-down（高风险，需人工审批，单步）。</li>
 * </ul>
 */
@Configuration
@EnableConfigurationProperties(OrchestratorProperties.class)
public class EngineConfiguration {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public ObjectMapper objectMapper() {
        return JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .build();
    }

    @Bean
    public TaskStore taskStore(OrchestratorProperties props, ObjectMapper mapper) {
        return new JsonTaskStore(Path.of(props.getDataDir()), mapper);
    }

    @Bean
    public AuditStore auditStore(OrchestratorProperties props, ObjectMapper mapper) {
        return new JsonlAuditStore(Path.of(props.getDataDir()), mapper);
    }

    @Bean(name = "local.cleanup")
    public RemediationAction cleanupAction() {
        return new NoopAction("local.cleanup");
    }

    @Bean(name = "local.restart")
    public RemediationAction restartAction() {
        return new NoopAction("local.restart");
    }

    @Bean
    public List<RemediationStrategy> strategies() {
        RemediationStrategy diskFull = new RemediationStrategy(
                "disk-full", 10, RiskLevel.LOW,
                new AlertMatcher("host-", "DISK_FULL", "KEYWORD", "disk,full,空间,磁盘"),
                List.of(
                        StepSpec.of("cleanup-logs", "local.cleanup", 2_000,
                                Map.of("target", "logs")),
                        StepSpec.of("verify-space", "local.cleanup", 2_000)),
                3, 200);

        RemediationStrategy serviceDown = new RemediationStrategy(
                "service-down", 20, RiskLevel.HIGH,
                new AlertMatcher("svc-", "SERVICE_DOWN", "KEYWORD", "down,unavailable,不可用"),
                List.of(StepSpec.of("restart-service", "local.restart", 2_000)),
                2, 200);

        return List.of(diskFull, serviceDown);
    }

    @Bean
    public StrategyMatcher strategyMatcher(List<RemediationStrategy> strategies) {
        return new StrategyMatcher(strategies);
    }

    @Bean
    public MaintenanceWindowService maintenanceWindowService(Clock clock) {
        return new MaintenanceWindowService(clock);
    }

    @Bean
    public DeduplicationService deduplicationService() {
        return new DeduplicationService();
    }

    @Bean
    public AuditService auditService(AuditStore auditStore, Clock clock) {
        return new AuditService(auditStore, clock);
    }

    @Bean
    public ApprovalService approvalService(TaskStore taskStore, AuditService auditService,
                                           Clock clock, OrchestratorProperties props) {
        return new ApprovalService(taskStore, auditService, clock, props.getApprovalTimeoutMs());
    }

    @Bean(name = "remediationTaskExecutor")
    public TaskExecutor remediationTaskExecutor(TaskStore taskStore, AuditService auditService,
                                     ApprovalService approvalService, Clock clock,
                                     Map<String, RemediationAction> actions) {
        return new TaskExecutor(taskStore, auditService, approvalService, clock, actions);
    }

    @Bean
    public TaskDispatcher taskDispatcher(TaskStore taskStore, TaskExecutor executor,
                                         ApprovalService approvalService, AuditService auditService,
                                         Clock clock, OrchestratorProperties props) {
        TaskDispatcher dispatcher = new TaskDispatcher(taskStore, executor, approvalService,
                auditService, clock, props.getGlobalConcurrency(),
                props.getApprovalTimeoutMs());
        approvalService.bindDispatcher(dispatcher);
        return dispatcher;
    }

    @Bean
    public RecoveryService recoveryService(TaskStore taskStore, TaskDispatcher dispatcher,
                                           ApprovalService approvalService,
                                           AuditService auditService, Clock clock) {
        return new RecoveryService(taskStore, dispatcher, approvalService, auditService, clock);
    }

    @Bean
    public AlertIntakeService alertIntakeService(DeduplicationService dedup,
                                                 MaintenanceWindowService windows,
                                                 StrategyMatcher matcher, TaskStore taskStore,
                                                 AuditService auditService,
                                                 TaskDispatcher dispatcher, Clock clock) {
        return new AlertIntakeService(dedup, windows, matcher, taskStore, auditService,
                dispatcher, clock);
    }
}
