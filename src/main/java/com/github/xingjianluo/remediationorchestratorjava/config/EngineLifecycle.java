package com.github.xingjianluo.remediationorchestratorjava.config;

import com.github.xingjianluo.remediationorchestratorjava.engine.ApprovalService;
import com.github.xingjianluo.remediationorchestratorjava.engine.RecoveryService;
import com.github.xingjianluo.remediationorchestratorjava.engine.TaskExecutor;
import com.github.xingjianluo.remediationorchestratorjava.engine.TaskDispatcher;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** 应用启动后执行恢复；关闭时有序停止调度/执行/审批线程。 */
@Component
public class EngineLifecycle {

    private static final Logger log = LoggerFactory.getLogger(EngineLifecycle.class);

    private final OrchestratorProperties props;
    private final RecoveryService recovery;
    private final TaskDispatcher dispatcher;
    private final TaskExecutor executor;
    private final ApprovalService approvals;

    public EngineLifecycle(OrchestratorProperties props, RecoveryService recovery,
                           TaskDispatcher dispatcher, TaskExecutor executor,
                           ApprovalService approvals) {
        this.props = props;
        this.recovery = recovery;
        this.dispatcher = dispatcher;
        this.executor = executor;
        this.approvals = approvals;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (props.isRecoverOnStartup()) {
            int n = recovery.recoverAll();
            log.info("[LIFECYCLE] startup recovery processed {} task(s)", n);
        }
    }

    @PreDestroy
    public void shutdown() {
        log.info("[LIFECYCLE] shutting down orchestration engines");
        dispatcher.shutdown();
        executor.shutdown();
        approvals.shutdown();
    }
}
