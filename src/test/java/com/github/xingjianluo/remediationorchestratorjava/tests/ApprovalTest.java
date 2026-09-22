package com.github.xingjianluo.remediationorchestratorjava.tests;

import com.github.xingjianluo.remediationorchestratorjava.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static com.github.xingjianluo.remediationorchestratorjava.tests.TestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** 高风险修复的人工审批：未批准不执行；批准/拒绝/超时/取消四态可区分且无半完成状态。 */
class ApprovalTest {

    private static final Logger log = LoggerFactory.getLogger(ApprovalTest.class);

    @TempDir
    Path tempDir;

    private TestFixtures.Env envWithApprovalPolicy() {
        var policies = List.of(policy("p-risk", List.of("restart"), true,
                List.of(step(StepType.MARKER, "risky-marker"))));
        return newEnv(policies, tempDir, 10_000L);
    }

    @Test
    void notExecutedBeforeApprovalThenRunsAfterGrant() throws Exception {
        var env = envWithApprovalPolicy();
        env.startDispatcher();
        String taskId = env.engine.accept(
                new Alert("ap-1", "host-R", "need restart please", "CRITICAL", 1)).taskId();
        log.info("输入告警需审批 task={}", taskId);

        Thread.sleep(150);
        RemediationTask waiting = env.store.findById(taskId).orElseThrow();
        assertEquals(TaskState.WAITING_APPROVAL, waiting.state);
        assertEquals(ApprovalStatus.PENDING, waiting.approval);
        // 批准前标记文件不得产生副作用
        long markersBefore = markerCount();
        log.info("关键状态: 批准前 state={} approval={}, marker 文件数={}",
                waiting.state, waiting.approval, markersBefore);
        assertEquals(0, markersBefore, "批准前不得执行任何修复动作");

        assertTrue(env.approvals.grant(taskId, "alice"));
        await(2000, () -> env.store.findById(taskId).orElseThrow().state == TaskState.SUCCEEDED);
        RemediationTask done = env.store.findById(taskId).orElseThrow();
        assertEquals(ApprovalStatus.APPROVED, done.approval);
        assertEquals(1, markerCount(), "批准后才应产生本地修复副作用");
        log.info("批准后关键迁移: state={} approval={}", done.state, done.approval);
        env.stopDispatcher();
    }

    @Test
    void rejectReachesDistinctTerminalState() {
        var env = envWithApprovalPolicy();
        String taskId = env.engine.accept(
                new Alert("ap-2", "host-R", "restart", "CRITICAL", 1)).taskId();

        assertTrue(env.approvals.reject(taskId, "bob", "change window closed"));
        RemediationTask task = env.store.findById(taskId).orElseThrow();
        log.info("拒绝后: state={} approval={} reason='{}'",
                task.state, task.approval, task.terminalReason);
        assertEquals(TaskState.APPROVAL_REJECTED, task.state);
        assertEquals(ApprovalStatus.REJECTED, task.approval);
        assertTrue(task.terminalReason.contains("rejected"));
        // 重复拒绝无效（状态已决定）
        assertFalse(env.approvals.reject(taskId, "bob", "again"));
    }

    @Test
    void timeoutReachesDistinctTerminalState() {
        var env = envWithApprovalPolicy();
        String taskId = env.engine.accept(
                new Alert("ap-3", "host-R", "restart", "CRITICAL", 1)).taskId();

        // 推进时钟超过审批截止，再让调度扫描（不启动 dispatcher，直接调用扫描）
        env.clock.advance(61_000L);
        int swept = env.approvals.sweepTimeouts(env.clock.instant().toEpochMilli());
        RemediationTask task = env.store.findById(taskId).orElseThrow();
        log.info("超时扫描: swept={}, state={}, approval={}, reason='{}'",
                swept, task.state, task.approval, task.terminalReason);
        assertEquals(1, swept);
        assertEquals(TaskState.APPROVAL_TIMEOUT, task.state);
        assertEquals(ApprovalStatus.TIMED_OUT, task.approval);
        assertTrue(task.terminalReason.contains("timed out"));
        assertNotEquals(TaskState.APPROVAL_REJECTED, task.state, "超时与拒绝必须可区分");
    }

    @Test
    void cancelWhileWaitingIsDistinctAndLeavesNoPartialWork() throws Exception {
        var env = envWithApprovalPolicy();
        String taskId = env.engine.accept(
                new Alert("ap-4", "host-R", "restart", "CRITICAL", 1)).taskId();

        assertTrue(env.engine.cancel(taskId, "operator aborted"));
        RemediationTask task = env.store.findById(taskId).orElseThrow();
        log.info("取消后: state={} approval={} reason='{}'",
                task.state, task.approval, task.terminalReason);
        assertEquals(TaskState.CANCELLED, task.state);
        assertEquals(ApprovalStatus.CANCELLED, task.approval);
        assertEquals(0, markerCount());
        // 所有步骤保持 CANCELLED/PENDING，不存在 RUNNING 残留
        assertTrue(task.steps.stream().noneMatch(s -> s.state == StepState.RUNNING),
                "取消后不得残留 RUNNING 步骤");
    }

    @Test
    void auditTrailDistinguishesTheThreeNegativeDecisions() {
        var env = envWithApprovalPolicy();
        String rejected = env.engine.accept(new Alert("ar", "h1", "restart", "CRITICAL", 1)).taskId();
        String timedOut = env.engine.accept(new Alert("at", "h2", "restart", "CRITICAL", 1)).taskId();
        String cancelled = env.engine.accept(new Alert("ac", "h3", "restart", "CRITICAL", 1)).taskId();

        env.approvals.reject(rejected, "bob", "no");
        // 先取消第三个任务，再推进时钟触发第二个任务的审批超时
        env.engine.cancel(cancelled, "abort");
        env.clock.advance(61_000L);
        env.approvals.sweepTimeouts(env.clock.instant().toEpochMilli());

        var events = env.audit.findAll();
        assertTrue(events.stream().anyMatch(e -> "APPROVAL_REJECTED".equals(e.event()) && rejected.equals(e.taskId())));
        assertTrue(events.stream().anyMatch(e -> "APPROVAL_TIMEOUT".equals(e.event()) && timedOut.equals(e.taskId())));
        assertTrue(events.stream().anyMatch(e -> "TASK_CANCELLED".equals(e.event()) && cancelled.equals(e.taskId())));
        log.info("审计中三类否定结论事件齐全: REJECTED / TIMEOUT / CANCELLED");
    }

    private long markerCount() throws Exception {
        try (var paths = Files.walk(tempDir.resolve("markers"))) {
            return paths.filter(Files::isRegularFile).count();
        } catch (java.nio.file.NoSuchFileException e) {
            return 0;
        }
    }
}
