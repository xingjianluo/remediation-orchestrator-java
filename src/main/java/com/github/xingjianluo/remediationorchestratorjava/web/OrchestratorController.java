package com.github.xingjianluo.remediationorchestratorjava.web;

import com.github.xingjianluo.remediationorchestratorjava.engine.AlertIntakeService;
import com.github.xingjianluo.remediationorchestratorjava.engine.ApprovalService;
import com.github.xingjianluo.remediationorchestratorjava.engine.AuditService;
import com.github.xingjianluo.remediationorchestratorjava.engine.MaintenanceWindowService;
import com.github.xingjianluo.remediationorchestratorjava.model.Alert;
import com.github.xingjianluo.remediationorchestratorjava.model.AlertDisposition;
import com.github.xingjianluo.remediationorchestratorjava.model.AuditEvent;
import com.github.xingjianluo.remediationorchestratorjava.model.MaintenanceWindow;
import com.github.xingjianluo.remediationorchestratorjava.model.RemediationTask;
import com.github.xingjianluo.remediationorchestratorjava.store.TaskStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 本地 HTTP 接口：受理告警、审批操作、登记维护窗口、查询任务与审计。 */
@RestController
@RequestMapping("/api")
public class OrchestratorController {

    private final AlertIntakeService intake;
    private final ApprovalService approvals;
    private final MaintenanceWindowService windows;
    private final TaskStore tasks;
    private final AuditService audit;

    public OrchestratorController(AlertIntakeService intake, ApprovalService approvals,
                                  MaintenanceWindowService windows, TaskStore tasks,
                                  AuditService audit) {
        this.intake = intake;
        this.approvals = approvals;
        this.windows = windows;
        this.tasks = tasks;
        this.audit = audit;
    }

    @PostMapping("/alerts")
    public AlertDisposition ingest(@RequestBody Map<String, String> body) {
        Alert alert = new Alert(
                body.getOrDefault("alertId", "alert-" + System.nanoTime()),
                body.get("target"), body.get("type"), body.get("content"));
        return intake.accept(alert);
    }

    @PostMapping("/tasks/{taskId}/approve")
    public ResponseEntity<?> approve(@PathVariable String taskId) {
        approvals.grant(taskId);
        return ResponseEntity.ok(Map.of("result", "granted", "taskId", taskId));
    }

    @PostMapping("/tasks/{taskId}/reject")
    public ResponseEntity<?> reject(@PathVariable String taskId) {
        approvals.reject(taskId);
        return ResponseEntity.ok(Map.of("result", "rejected", "taskId", taskId));
    }

    @PostMapping("/tasks/{taskId}/cancel")
    public ResponseEntity<?> cancel(@PathVariable String taskId) {
        approvals.cancel(taskId);
        return ResponseEntity.ok(Map.of("result", "cancelled", "taskId", taskId));
    }

    @PostMapping("/maintenance-windows")
    public MaintenanceWindow registerWindow(@RequestBody Map<String, String> body) {
        return windows.register(body.get("id"), body.get("targetPrefix"),
                Instant.parse(body.get("start")), Instant.parse(body.get("end")));
    }

    @GetMapping("/tasks")
    public List<RemediationTask> listTasks() {
        return tasks.findAll();
    }

    @GetMapping("/tasks/{taskId}")
    public ResponseEntity<RemediationTask> getTask(@PathVariable String taskId) {
        return tasks.findById(taskId).map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/tasks/{taskId}/audit")
    public List<AuditEvent> taskAudit(@PathVariable String taskId) {
        return audit.byTask(taskId);
    }

    @GetMapping("/audit")
    public List<AuditEvent> allAudit() {
        return audit.all();
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }
}
