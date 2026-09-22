package com.github.xingjianluo.remediationorchestratorjava.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.xingjianluo.remediationorchestratorjava.approval.ApprovalService;
import com.github.xingjianluo.remediationorchestratorjava.engine.PolicyMatcher;
import com.github.xingjianluo.remediationorchestratorjava.engine.RecoveryService;
import com.github.xingjianluo.remediationorchestratorjava.engine.TaskDispatcher;
import com.github.xingjianluo.remediationorchestratorjava.engine.TaskEngine;
import com.github.xingjianluo.remediationorchestratorjava.executor.LocalStepHandlers;
import com.github.xingjianluo.remediationorchestratorjava.executor.StepExecutor;
import com.github.xingjianluo.remediationorchestratorjava.maintenance.MaintenanceWindowService;
import com.github.xingjianluo.remediationorchestratorjava.model.RemediationPolicy;
import com.github.xingjianluo.remediationorchestratorjava.store.AuditLog;
import com.github.xingjianluo.remediationorchestratorjava.store.FileAuditLog;
import com.github.xingjianluo.remediationorchestratorjava.store.FileTaskStore;
import com.github.xingjianluo.remediationorchestratorjava.store.TaskStore;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;

/** 编排器 Spring 装配。 */
@Configuration
@EnableConfigurationProperties(OrchestratorProperties.class)
public class OrchestratorConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public ObjectMapper orchestratorObjectMapper() {
        return new ObjectMapper();
    }

    @Bean
    public TaskStore taskStore(OrchestratorProperties props, ObjectMapper mapper) {
        FileTaskStore store = new FileTaskStore(Path.of(props.getDataDir(), "tasks"), mapper);
        store.init();
        return store;
    }

    @Bean
    public AuditLog auditLog(OrchestratorProperties props, ObjectMapper mapper) {
        FileAuditLog log = new FileAuditLog(Path.of(props.getDataDir(), "audit.jsonl"), mapper);
        log.init();
        return log;
    }

    @Bean
    public List<RemediationPolicy> remediationPolicies(OrchestratorProperties props) {
        return new PolicyLoader().load(props.getPoliciesLocation());
    }

    @Bean
    public MaintenanceWindowService maintenanceWindowService(OrchestratorProperties props) {
        MaintenanceWindowService service = new MaintenanceWindowService();
        String override = props.getMaintenanceFile();
        Path file = (override == null || override.isBlank())
                ? Path.of(props.getDataDir(), "maintenance.json")
                : Path.of(override);
        service.loadFromFile(file);
        return service;
    }

    @Bean
    public PolicyMatcher policyMatcher(List<RemediationPolicy> policies) {
        return new PolicyMatcher(policies);
    }

    @Bean
    public StepExecutor stepExecutor(OrchestratorProperties props) {
        return new StepExecutor(LocalStepHandlers.defaults(Path.of(props.getDataDir())));
    }

    @Bean
    public ApprovalService approvalService(TaskStore taskStore, AuditLog auditLog, OrchestratorProperties props) {
        return new ApprovalService(taskStore, auditLog, props);
    }

    @Bean
    public TaskEngine taskEngine(TaskStore taskStore, AuditLog auditLog, PolicyMatcher matcher,
                                 MaintenanceWindowService maintenance, ApprovalService approvalService,
                                 OrchestratorProperties props, Clock clock) {
        TaskEngine engine = new TaskEngine(taskStore, auditLog, matcher, maintenance, props, clock);
        engine.setApprovalService(approvalService);
        return engine;
    }

    @Bean
    public RecoveryService recoveryService(TaskStore taskStore, AuditLog auditLog, Clock clock) {
        return new RecoveryService(taskStore, auditLog, clock);
    }

    @Bean
    public TaskDispatcher taskDispatcher(TaskStore taskStore, AuditLog auditLog, StepExecutor stepExecutor,
                                         ApprovalService approvalService, RecoveryService recoveryService,
                                         OrchestratorProperties props, Clock clock) {
        return new TaskDispatcher(taskStore, auditLog, stepExecutor, approvalService, recoveryService, props, clock);
    }
}
