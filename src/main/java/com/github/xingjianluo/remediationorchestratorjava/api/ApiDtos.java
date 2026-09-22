package com.github.xingjianluo.remediationorchestratorjava.api;

/** REST DTO 占位。 */
public final class ApiDtos {
    private ApiDtos() {
    }

    public record AlertRequest(String id, String target, String content, String severity, Long occurredAt) {
    }

    public record DecisionRequest(String approver, String reason) {
    }

    public record CancelRequest(String reason) {
    }

    public record MaintenanceRequest(String targetPattern, Long startMillis, Long endMillis, String reason) {
    }
}
