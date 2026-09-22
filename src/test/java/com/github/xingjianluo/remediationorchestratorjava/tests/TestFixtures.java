package com.github.xingjianluo.remediationorchestratorjava.tests;

import com.github.xingjianluo.remediationorchestratorjava.approval.ApprovalService;
import com.github.xingjianluo.remediationorchestratorjava.config.OrchestratorProperties;
import com.github.xingjianluo.remediationorchestratorjava.engine.PolicyMatcher;
import com.github.xingjianluo.remediationorchestratorjava.engine.RecoveryService;
import com.github.xingjianluo.remediationorchestratorjava.engine.TaskDispatcher;
import com.github.xingjianluo.remediationorchestratorjava.engine.TaskEngine;
import com.github.xingjianluo.remediationorchestratorjava.executor.LocalStepHandlers;
import com.github.xingjianluo.remediationorchestratorjava.executor.StepExecutor;
import com.github.xingjianluo.remediationorchestratorjava.maintenance.MaintenanceWindowService;
import com.github.xingjianluo.remediationorchestratorjava.model.RemediationPolicy;
import com.github.xingjianluo.remediationorchestratorjava.model.StepSpec;
import com.github.xingjianluo.remediationorchestratorjava.model.StepType;
import com.github.xingjianluo.remediationorchestratorjava.store.AuditLog;
import com.github.xingjianluo.remediationorchestratorjava.store.InMemoryAuditLog;
import com.github.xingjianluo.remediationorchestratorjava.store.InMemoryTaskStore;
import com.github.xingjianluo.remediationorchestratorjava.store.TaskStore;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** 单元测试夹具：内存存储 + 可推进时钟 + 直接可调用的引擎/调度器。 */
final class TestFixtures {

    /** 可手动设置时间的时钟（毫秒精度）。 */
    static final class MutableClock extends Clock {
        private long now;

        MutableClock(long start) {
            this.now = start;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(now);
        }

        void set(long millis) {
            this.now = millis;
        }

        void advance(long millis) {
            this.now += millis;
        }
    }

    static final class Env {
        OrchestratorProperties props;
        TaskStore store;
        AuditLog audit;
        MaintenanceWindowService maintenance;
        ApprovalService approvals;
        TaskEngine engine;
        RecoveryService recovery;
        TaskDispatcher dispatcher;
        StepExecutor stepExecutor;
        MutableClock clock;
        Path dataDir;
        List<RemediationPolicy> policies;

        void startDispatcher() {
            dispatcher.start();
        }

        void stopDispatcher() {
            dispatcher.stop();
        }
    }

    static Env newEnv(List<RemediationPolicy> policies, Path dataDir) {
        return newEnv(policies, dataDir, 10_000L);
    }

    static Env newEnv(List<RemediationPolicy> policies, Path dataDir, long startTime) {
        Env env = new Env();
        env.policies = policies;
        env.dataDir = dataDir;
        env.props = new OrchestratorProperties();
        env.props.setMaxConcurrent(2);
        env.props.setTickMillis(20);
        env.props.setDefaultMaxRetries(2);
        env.props.setDefaultBackoffBaseMillis(20);
        env.props.setDefaultStepTimeoutMillis(1000);
        env.props.setDefaultApprovalTimeoutSeconds(60);
        env.store = new InMemoryTaskStore();
        env.audit = new InMemoryAuditLog();
        env.clock = new MutableClock(startTime);
        env.maintenance = new MaintenanceWindowService();
        env.approvals = new ApprovalService(env.store, env.audit, env.props, env.clock);
        env.engine = new TaskEngine(env.store, env.audit, new PolicyMatcher(policies),
                env.maintenance, env.props, env.clock);
        env.engine.setApprovalService(env.approvals);
        env.stepExecutor = new StepExecutor(LocalStepHandlers.defaults(dataDir));
        env.recovery = new RecoveryService(env.store, env.audit, env.clock);
        env.dispatcher = new TaskDispatcher(env.store, env.audit, env.stepExecutor,
                env.approvals, env.recovery, env.props, env.clock);
        return env;
    }

    static RemediationPolicy policy(String id, List<String> keywords, boolean approval,
                                    List<StepSpec> steps) {
        RemediationPolicy p = new RemediationPolicy();
        p.id = id;
        p.matchAnyKeywords = new ArrayList<>(keywords);
        p.requiresApproval = approval;
        p.approvalTimeoutSeconds = 60;
        p.maxRetries = 0;
        p.backoffBaseMillis = 10;
        p.stepTimeoutMillis = 1000;
        p.steps = new ArrayList<>(steps);
        return p;
    }

    static StepSpec step(StepType type, String name) {
        return step(type, name, Map.of());
    }

    static StepSpec step(StepType type, String name, Map<String, String> params) {
        StepSpec s = new StepSpec();
        s.type = type;
        s.name = name;
        s.params = params;
        return s;
    }

    /** 在断言前等待条件成立（真实线程 + 真实 sleep 的测试使用）。 */
    static void await(long timeoutMillis, Supplier<Boolean> condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.get()) {
                return;
            }
            Thread.sleep(20);
        }
        if (!condition.get()) {
            throw new AssertionError("condition not met within " + timeoutMillis + "ms");
        }
    }

    private TestFixtures() {
    }
}
