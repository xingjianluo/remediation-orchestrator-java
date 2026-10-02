package com.github.xingjianluo.remediationorchestratorjava.api;

import com.github.xingjianluo.remediationorchestratorjava.approval.ApprovalService;
import com.github.xingjianluo.remediationorchestratorjava.config.MaintenanceWindow;
import com.github.xingjianluo.remediationorchestratorjava.engine.TaskEngine;
import com.github.xingjianluo.remediationorchestratorjava.maintenance.MaintenanceWindowService;
import com.github.xingjianluo.remediationorchestratorjava.model.AcceptResult;
import com.github.xingjianluo.remediationorchestratorjava.model.Alert;
import com.github.xingjianluo.remediationorchestratorjava.model.AuditEvent;
import com.github.xingjianluo.remediationorchestratorjava.model.DispositionCode;
import com.github.xingjianluo.remediationorchestratorjava.model.RemediationTask;
import com.github.xingjianluo.remediationorchestratorjava.recurrence.RecurrenceService;
import com.github.xingjianluo.remediationorchestratorjava.recurrence.RecurrenceStatus;
import com.github.xingjianluo.remediationorchestratorjava.store.AuditLog;
import com.github.xingjianluo.remediationorchestratorjava.store.TaskStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.List;
import java.util.Map;

/** 本地 HTTP 接口：告警受理、审批决策、取消、查询、维护窗口管理。不依赖任何外部服务。 */
@RestController
@RequestMapping("/api")
public class OrchestratorController {

    private static final Logger log = LoggerFactory.getLogger(OrchestratorController.class);

    private final TaskEngine engine;
    private final TaskStore taskStore;
    private final AuditLog auditLog;
    private final ApprovalService approvalService;
    private final MaintenanceWindowService maintenance;
    private final RecurrenceService recurrenceService;
    private final Clock clock;

    public OrchestratorController(TaskEngine engine, TaskStore taskStore, AuditLog auditLog,
                                  ApprovalService approvalService, MaintenanceWindowService maintenance,
                                  RecurrenceService recurrenceService, Clock clock) {
        this.engine = engine;
        this.taskStore = taskStore;
        this.auditLog = auditLog;
        this.approvalService = approvalService;
        this.maintenance = maintenance;
        this.recurrenceService = recurrenceService;
        this.clock = clock;
    }

    @PostMapping("/alerts")
    public ResponseEntity<AcceptResult> accept(@RequestBody ApiDtos.AlertRequest req) {
        long occurredAt = req.occurredAt() != null ? req.occurredAt() : clock.millis();
        Alert alert = new Alert(
                req.id() == null || req.id().isBlank() ? "alert-" + java.util.UUID.randomUUID() : req.id(),
                req.target(), req.content() == null ? "" : req.content(),
                req.severity() == null ? "WARN" : req.severity(), occurredAt);
        AcceptResult result = engine.accept(alert);
        HttpStatus status = result.disposition() == DispositionCode.CREATED
                ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(result);
    }

    @PostMapping("/tasks/{taskId}/approve")
    public ResponseEntity<Map<String, Object>> approve(@PathVariable String taskId,
                                                       @RequestBody(required = false) ApiDtos.DecisionRequest req) {
        String approver = req == null ? "" : req.approver();
        boolean ok = approvalService.grant(taskId, approver);
        return decisionResponse(ok, taskId);
    }

    @PostMapping("/tasks/{taskId}/reject")
    public ResponseEntity<Map<String, Object>> reject(@PathVariable String taskId,
                                                      @RequestBody(required = false) ApiDtos.DecisionRequest req) {
        String approver = req == null ? "" : req.approver();
        String reason = req == null ? "" : req.reason();
        boolean ok = approvalService.reject(taskId, approver, reason);
        return decisionResponse(ok, taskId);
    }

    @PostMapping("/tasks/{taskId}/cancel")
    public ResponseEntity<Map<String, Object>> cancel(@PathVariable String taskId,
                                                      @RequestBody(required = false) ApiDtos.CancelRequest req) {
        String reason = req == null ? "" : req.reason();
        boolean ok = engine.cancel(taskId, reason);
        return decisionResponse(ok, taskId);
    }

    @GetMapping("/tasks/{taskId}")
    public ResponseEntity<RemediationTask> getTask(@PathVariable String taskId) {
        return taskStore.findById(taskId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/tasks")
    public List<RemediationTask> listTasks() {
        return taskStore.findAll();
    }

    @GetMapping("/audit")
    public List<AuditEvent> audit(@RequestParam(required = false) String taskId) {
        return taskId == null ? auditLog.findAll() : auditLog.findByTaskId(taskId);
    }

    @GetMapping("/maintenance")
    public List<MaintenanceWindow> listMaintenance() {
        return maintenance.listWindows();
    }

    @PostMapping("/maintenance")
    public MaintenanceWindow addMaintenance(@RequestBody ApiDtos.MaintenanceRequest req) {
        long now = clock.millis();
        long start = req.startMillis() != null ? req.startMillis() : now;
        long end = req.endMillis() != null ? req.endMillis() : now + 3_600_000L;
        return maintenance.addWindow(
                req.targetPattern() == null ? "*" : req.targetPattern(), start, end, req.reason());
    }

    @DeleteMapping("/maintenance/{id}")
    public ResponseEntity<Map<String, Object>> removeMaintenance(@PathVariable String id) {
        boolean removed = maintenance.removeWindow(id);
        return ResponseEntity.ok(Map.of("removed", removed));
    }

    /** 查询全部目标的复发/升级状态（含当前复发计数与所依据的时间窗）。 */
    @GetMapping("/recurrence")
    public List<RecurrenceStatus> listRecurrence() {
        return recurrenceService.allStatus();
    }

    @GetMapping("/recurrence/{target}")
    public ResponseEntity<RecurrenceStatus> getRecurrence(@PathVariable String target) {
        return ResponseEntity.ok(recurrenceService.status(target));
    }

    /** 人工确认：放行该目标下一次复发的一次真实修复。 */
    @PostMapping("/recurrence/{target}/confirm")
    public RecurrenceStatus confirmRecurrence(@PathVariable String target,
                                              @RequestBody(required = false) ApiDtos.DecisionRequest req) {
        recurrenceService.confirm(target, req == null ? "" : req.approver());
        return recurrenceService.status(target);
    }

    /** 人工放弃：统计窗内该目标的复发不再自动修复。 */
    @PostMapping("/recurrence/{target}/abandon")
    public RecurrenceStatus abandonRecurrence(@PathVariable String target,
                                              @RequestBody(required = false) ApiDtos.DecisionRequest req) {
        recurrenceService.abandon(target, req == null ? "" : req.approver(), req == null ? "" : req.reason());
        return recurrenceService.status(target);
    }

    private ResponseEntity<Map<String, Object>> decisionResponse(boolean ok, String taskId) {
        if (!ok) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("ok", false, "taskId", taskId,
                            "message", "decision not applicable in current state"));
        }
        return taskStore.findById(taskId)
                .map(t -> ResponseEntity.ok(Map.<String, Object>of(
                        "ok", true, "taskId", taskId, "state", t.state.name(), "approval", t.approval.name())))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("ok", false, "taskId", taskId)));
    }
}
