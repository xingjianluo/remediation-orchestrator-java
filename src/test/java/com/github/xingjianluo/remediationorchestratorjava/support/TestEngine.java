package com.github.xingjianluo.remediationorchestratorjava.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.xingjianluo.remediationorchestratorjava.action.RemediationAction;
import com.github.xingjianluo.remediationorchestratorjava.engine.AlertIntakeService;
import com.github.xingjianluo.remediationorchestratorjava.engine.ApprovalService;
import com.github.xingjianluo.remediationorchestratorjava.engine.AuditService;
import com.github.xingjianluo.remediationorchestratorjava.engine.DeduplicationService;
import com.github.xingjianluo.remediationorchestratorjava.engine.MaintenanceWindowService;
import com.github.xingjianluo.remediationorchestratorjava.engine.RecoveryService;
import com.github.xingjianluo.remediationorchestratorjava.engine.StrategyMatcher;
import com.github.xingjianluo.remediationorchestratorjava.engine.TaskDispatcher;
import com.github.xingjianluo.remediationorchestratorjava.engine.TaskExecutor;
import com.github.xingjianluo.remediationorchestratorjava.model.RemediationStrategy;
import com.github.xingjianluo.remediationorchestratorjava.store.AuditStore;
import com.github.xingjianluo.remediationorchestratorjava.store.JsonTaskStore;
import com.github.xingjianluo.remediationorchestratorjava.store.JsonlAuditStore;
import com.github.xingjianluo.remediationorchestratorjava.store.TaskStore;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;

/**
 * 手工装配的编排引擎（不启动 Spring），便于在测试中：
 * <ul>
 *   <li>注入可编排的 {@link RecordingAction}；</li>
 *   <li>控制并发上限、审批超时；</li>
 *   <li>用同一数据目录 {@link #restart(boolean)} 模拟进程重启（旧实例立即 shutdownNow）。</li>
 * </ul>
 * 每个实例在 parentDir 下使用唯一子目录，测试方法间完全隔离。
 */
public class TestEngine implements AutoCloseable {

    public final Path dataDir;
    public final TaskStore taskStore;
    public final AuditStore auditStore;
    public final AuditService audit;
    public final MaintenanceWindowService windows;
    public final AlertIntakeService intake;
    public final ApprovalService approvals;
    public final TaskDispatcher dispatcher;
    public final TaskExecutor executor;
    public final RecoveryService recovery;
    public final StrategyMatcher matcher;
    public final Map<String, RemediationAction> actions;

    private final ObjectMapper mapper;

    private TestEngine(Path dataDir, ObjectMapper mapper,
                       Map<String, RemediationAction> actions,
                       List<RemediationStrategy> strategies,
                       int concurrency, long approvalTimeoutMs, Clock clock) {
        this.dataDir = dataDir;
        this.mapper = mapper;
        this.actions = actions;

        this.taskStore = new JsonTaskStore(dataDir, mapper);
        this.auditStore = new JsonlAuditStore(dataDir, mapper);
        this.audit = new AuditService(auditStore, clock);
        this.windows = new MaintenanceWindowService(clock);
        this.matcher = new StrategyMatcher(strategies);
        DeduplicationService dedup = new DeduplicationService();
        this.approvals = new ApprovalService(taskStore, audit, clock, approvalTimeoutMs);
        Map<String, RemediationAction> beanMap = Map.copyOf(actions);
        this.executor = new TaskExecutor(taskStore, audit, approvals, clock, beanMap);
        this.dispatcher = new TaskDispatcher(taskStore, executor, approvals, audit, clock,
                concurrency, approvalTimeoutMs);
        approvals.bindDispatcher(this.dispatcher);
        this.recovery = new RecoveryService(taskStore, dispatcher, approvals, audit, clock);
        this.intake = new AlertIntakeService(dedup, windows, matcher, taskStore, audit,
                dispatcher, clock);
    }

    public static TestEngine create(Path parentDir,
                                    Map<String, RemediationAction> actions,
                                    List<RemediationStrategy> strategies) {
        return create(parentDir, actions, strategies, 2, 60_000, true);
    }

    public static TestEngine create(Path parentDir,
                                    Map<String, RemediationAction> actions,
                                    List<RemediationStrategy> strategies,
                                    int concurrency, long approvalTimeoutMs,
                                    boolean autoRecover) {
        ObjectMapper mapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();
        Path dir = parentDir.resolve("engine-" + java.util.UUID.randomUUID());
        Map<String, RemediationAction> copied = Map.copyOf(actions);
        TestEngine engine = new TestEngine(dir, mapper, copied, strategies,
                concurrency, approvalTimeoutMs, Clock.systemUTC());
        if (autoRecover) {
            engine.recovery.recoverAll();
        }
        return engine;
    }

    /**
     * 模拟进程重启：先立即 shutdownNow 旧线程（模拟被杀死），再用同一数据目录创建新实例。
     * 复用相同的 action 与策略定义。
     */
    public TestEngine restart(List<RemediationStrategy> strategies,
                              int concurrency, long approvalTimeoutMs) {
        this.dispatcher.shutdown();
        this.executor.shutdown();
        this.approvals.shutdown();
        // 模拟新进程：清除旧进程遗留的测试动作阻塞状态（执行计数保留，用于断言不重复执行）
        actions.values().stream()
                .filter(a -> a instanceof RecordingAction)
                .map(a -> (RecordingAction) a)
                .forEach(RecordingAction::clearBlocks);
        TestEngine fresh = new TestEngine(dataDir, mapper, actions, strategies,
                concurrency, approvalTimeoutMs, Clock.systemUTC());
        fresh.recovery.recoverAll();
        return fresh;
    }

    @Override
    public void close() {
        dispatcher.shutdown();
        executor.shutdown();
        approvals.shutdown();
    }
}
