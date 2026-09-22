package com.github.xingjianluo.remediationorchestratorjava.executor;

import com.github.xingjianluo.remediationorchestratorjava.model.RemediationTask;
import com.github.xingjianluo.remediationorchestratorjava.model.StepType;
import com.github.xingjianluo.remediationorchestratorjava.model.TaskStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.EnumMap;
import java.util.Map;

/**
 * 仅使用本地资源的内置步骤处理器：
 * <ul>
 *   <li>MARKER：向本地文件追加一行标记，副作用可观测、可断言；</li>
 *   <li>NOOP：恒成功的空操作；</li>
 *   <li>FLAKY：前 N 次尝试失败（按任务内共享文件计数），之后成功，用于验证重试；</li>
 *   <li>ALWAYS_FAIL：恒失败，用于验证终态与错误信息留存；</li>
 *   <li>SLOW：睡眠指定毫秒，用于验证步骤超时与中断。</li>
 * </ul>
 */
public final class LocalStepHandlers {

    private static final Logger log = LoggerFactory.getLogger(LocalStepHandlers.class);

    private LocalStepHandlers() {
    }

    public static Map<StepType, StepHandler> defaults(Path dataDir) {
        EnumMap<StepType, StepHandler> map = new EnumMap<>(StepType.class);
        MarkerHandler marker = new MarkerHandler(dataDir);
        map.put(StepType.MARKER, marker);
        map.put(StepType.NOOP, new NoopHandler());
        map.put(StepType.FLAKY, new FlakyHandler(dataDir));
        map.put(StepType.ALWAYS_FAIL, new AlwaysFailHandler());
        map.put(StepType.SLOW, new SlowHandler());
        return map;
    }

    static String param(TaskStep step, String key, String fallback) {
        if (step.params == null) {
            return fallback;
        }
        String v = step.params.get(key);
        return v == null ? fallback : v;
    }

    static final class MarkerHandler implements StepHandler {
        private final Path markerDir;

        MarkerHandler(Path dataDir) {
            this.markerDir = dataDir.resolve("markers");
        }

        @Override
        public StepType type() {
            return StepType.MARKER;
        }

        @Override
        public StepExecutionResult execute(TaskStep step, RemediationTask task) throws InterruptedException {
            try {
                Files.createDirectories(markerDir);
                String message = param(step, "message", step.name);
                String line = System.currentTimeMillis() + " task=" + task.id + " target=" + task.target
                        + " step=" + step.index + " msg=" + message + System.lineSeparator();
                Path file = markerDir.resolve(task.target.replaceAll("[^A-Za-z0-9._-]", "_") + ".log");
                Files.writeString(file, line, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                log.info("[step] MARKER appended to {} for task={}", file, task.id);
                return StepExecutionResult.ok();
            } catch (Exception e) {
                return StepExecutionResult.fail("MARKER failed: " + e);
            }
        }
    }

    static final class NoopHandler implements StepHandler {
        @Override
        public StepType type() {
            return StepType.NOOP;
        }

        @Override
        public StepExecutionResult execute(TaskStep step, RemediationTask task) {
            log.info("[step] NOOP task={} step={}", task.id, step.index);
            return StepExecutionResult.ok();
        }
    }

    static final class FlakyHandler implements StepHandler {
        private final Path stateDir;

        FlakyHandler(Path dataDir) {
            this.stateDir = dataDir.resolve("flaky");
        }

        @Override
        public StepType type() {
            return StepType.FLAKY;
        }

        @Override
        public StepExecutionResult execute(TaskStep step, RemediationTask task) throws InterruptedException {
            int failFirst;
            try {
                failFirst = Integer.parseInt(param(step, "failFirst", "2"));
            } catch (NumberFormatException e) {
                failFirst = 2;
            }
            try {
                Files.createDirectories(stateDir);
                Path counter = stateDir.resolve(task.id + "-" + step.index + ".cnt");
                int seen = Files.exists(counter)
                        ? Integer.parseInt(Files.readString(counter, StandardCharsets.UTF_8).trim())
                        : 0;
                seen++;
                Files.writeString(counter, Integer.toString(seen), StandardCharsets.UTF_8);
                log.info("[step] FLAKY task={} step={} attempt={} failFirst={}",
                        task.id, step.index, seen, failFirst);
                if (seen <= failFirst) {
                    return StepExecutionResult.fail("simulated flaky failure #" + seen);
                }
                return StepExecutionResult.ok();
            } catch (Exception e) {
                return StepExecutionResult.fail("FLAKY state error: " + e);
            }
        }
    }

    static final class AlwaysFailHandler implements StepHandler {
        @Override
        public StepType type() {
            return StepType.ALWAYS_FAIL;
        }

        @Override
        public StepExecutionResult execute(TaskStep step, RemediationTask task) {
            String message = param(step, "message", "permanent simulated failure");
            log.info("[step] ALWAYS_FAIL task={} step={}: {}", task.id, step.index, message);
            return StepExecutionResult.fail(message);
        }
    }

    static final class SlowHandler implements StepHandler {
        @Override
        public StepType type() {
            return StepType.SLOW;
        }

        @Override
        public StepExecutionResult execute(TaskStep step, RemediationTask task) throws InterruptedException {
            long sleepMillis;
            try {
                sleepMillis = Long.parseLong(param(step, "sleepMillis", "10000"));
            } catch (NumberFormatException e) {
                sleepMillis = 10000;
            }
            log.info("[step] SLOW task={} step={} sleeping {}ms", task.id, step.index, sleepMillis);
            try {
                Thread.sleep(sleepMillis);
                return StepExecutionResult.ok();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.info("[step] SLOW interrupted task={} step={}", task.id, step.index);
                throw e;
            }
        }
    }
}
