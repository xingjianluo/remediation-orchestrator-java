package com.github.xingjianluo.remediationorchestratorjava.engine;

/**
 * 工作线程在执行任务期间被中断（用于在测试中模拟进程被杀死）。
 * 抛出时任务保持当前（通常为 RUNNING）持久化状态，重启后可恢复，不会进入终态。
 */
public class TaskAbortedException extends RuntimeException {

    public TaskAbortedException(String taskId) {
        super("task aborted while still recoverable: " + taskId);
    }
}
