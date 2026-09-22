package com.github.xingjianluo.remediationorchestratorjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 全链路集成测试：HTTP 告警受理 -> 高风险任务等待审批 -> 人工批准 -> 执行成功 -> 审计可查。
 * 使用独立临时数据目录，不依赖任何外部服务。
 */
@SpringBootTest(properties = {
        "remediation.orchestrator.data-dir=target/e2e-data",
        "remediation.orchestrator.tick-millis=30",
        "remediation.orchestrator.default-backoff-base-millis=20",
        "remediation.orchestrator.default-step-timeout-millis=2000",
        "remediation.orchestrator.default-approval-timeout-seconds=3600"
})
@AutoConfigureMockMvc
class EndToEndIntegrationTest {

    @Autowired
    MockMvc mvc;
    final ObjectMapper json = new ObjectMapper();

    @Test
    void riskyAlertWaitsForApprovalThenCompletesAfterGrant() throws Exception {
        // 高危重启告警 -> 等待审批
        String body = """
                {"id":"e2e-restart-1","target":"e2e-host","content":"please restart the node","severity":"CRITICAL"}
                """;
        String resp = mvc.perform(post("/api/alerts").contentType("application/json").content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.disposition").value("CREATED"))
                .andReturn().getResponse().getContentAsString();
        String taskId = json.readTree(resp).get("taskId").asText();

        // 批准前查询：等待审批
        mvc.perform(get("/api/tasks/" + taskId))
                .andExpect(jsonPath("$.state").value("WAITING_APPROVAL"))
                .andExpect(jsonPath("$.approval").value("PENDING"));

        // 重复告警被合并
        String dup = """
                {"id":"e2e-restart-dup","target":"e2e-host","content":"please restart the node","severity":"CRITICAL"}
                """;
        mvc.perform(post("/api/alerts").contentType("application/json").content(dup))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.disposition").value("DUPLICATE"))
                .andExpect(jsonPath("$.taskId").value(taskId));

        // 批准
        mvc.perform(post("/api/tasks/" + taskId + "/approve")
                        .contentType("application/json")
                        .content("{\"approver\":\"oncall\",\"reason\":\"LGTM\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));

        // 终态轮询
        String state = "";
        for (int i = 0; i < 100; i++) {
            String taskJson = mvc.perform(get("/api/tasks/" + taskId))
                    .andReturn().getResponse().getContentAsString();
            JsonNode node = json.readTree(taskJson);
            state = node.get("state").asText();
            if ("SUCCEEDED".equals(state)) {
                break;
            }
            Thread.sleep(30);
        }
        org.junit.jupiter.api.Assertions.assertEquals("SUCCEEDED", state);

        // 审计链完整：受理 -> 审批请求 -> 批准 -> 派发 -> 步骤 -> 成功
        String auditResp = mvc.perform(get("/api/audit").param("taskId", taskId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode events = json.readTree(auditResp);
        java.util.List<String> names = events.findValues("event").stream()
                .map(JsonNode::asText).toList();
        org.junit.jupiter.api.Assertions.assertTrue(names.contains("APPROVAL_REQUESTED"));
        org.junit.jupiter.api.Assertions.assertTrue(names.contains("APPROVAL_GRANTED"));
        org.junit.jupiter.api.Assertions.assertTrue(names.contains("TASK_DISPATCHED"));
        org.junit.jupiter.api.Assertions.assertTrue(names.contains("TASK_SUCCEEDED"));
    }

    @Test
    void maintenanceWindowSuppressesViaHttp() throws Exception {
        mvc.perform(post("/api/maintenance").contentType("application/json").content("""
                        {"targetPattern":"e2e-mw-host","reason":"e2e freeze"}
                        """))
                .andExpect(status().isOk());

        mvc.perform(post("/api/alerts").contentType("application/json").content("""
                        {"id":"e2e-mw-1","target":"e2e-mw-host","content":"disk full","severity":"CRITICAL"}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.disposition").value("SUPPRESSED_MAINTENANCE"));
    }
}
