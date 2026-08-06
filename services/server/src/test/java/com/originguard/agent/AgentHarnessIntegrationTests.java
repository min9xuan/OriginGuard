package com.originguard.agent;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Testcontainers
class AgentHarnessIntegrationTests {
    private static final String PASSWORD = "OriginGuard@123";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    @Autowired
    MockMvc mockMvc;

    @Test
    void fakePlannerSkillMockToolCheckpointAndTraceCompleteVerticalSlice() throws Exception {
        String investigator = token("investigator");
        String reviewer = token("reviewer");
        String caseId = investigatingCase(investigator, "Agent Harness 纵向切片");

        MvcResult created = mockMvc.perform(post("/api/v1/agent-tasks")
                        .header("Authorization", bearer(investigator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"caseId":"%s","goal":"检查案件媒体元数据并形成证据","stepBudget":3}
                                """.formatted(caseId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.task.status").value("PENDING"))
                .andExpect(jsonPath("$.task.remainingStepBudget").value(3))
                .andReturn();
        String taskId = JsonPath.read(created.getResponse().getContentAsString(), "$.task.id");

        mockMvc.perform(post("/api/v1/agent-tasks/{id}/run", taskId)
                        .header("Authorization", bearer(investigator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.status").value("COMPLETED"))
                .andExpect(jsonPath("$.task.selectedSkillCode").value("inspect_media_metadata"))
                .andExpect(jsonPath("$.task.selectedSkillVersion").value("1.0.0"))
                .andExpect(jsonPath("$.task.remainingStepBudget").value(0))
                .andExpect(jsonPath("$.task.checkpointVersion").value(1))
                .andExpect(jsonPath("$.task.conclusion.verdict").value("INCONCLUSIVE"))
                .andExpect(jsonPath("$.steps.length()").value(7))
                .andExpect(jsonPath("$.steps[*].stepType", hasItem("TOOL_CALLED")))
                .andExpect(jsonPath("$.steps[*].stepType", hasItem("CHECKPOINT_SAVED")))
                .andExpect(jsonPath("$.observations.length()").value(1))
                .andExpect(jsonPath("$.observations[0].evidenceType").value("MEDIA_METADATA"))
                .andExpect(jsonPath("$.observations[0].payload.provider").value("MOCK"))
                .andExpect(jsonPath("$.observations[0].payload.fileContentInspected").value(false))
                .andExpect(jsonPath("$.checkpoints.length()").value(1))
                .andExpect(jsonPath("$.checkpoints[0].state.remainingStepBudget").value(1));

        mockMvc.perform(get("/api/v1/agent-tasks/{id}", taskId)
                        .header("Authorization", bearer(reviewer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.status").value("COMPLETED"))
                .andExpect(jsonPath("$.steps.length()").value(7));

        mockMvc.perform(get("/api/v1/cases/{id}/audit", caseId)
                        .header("Authorization", bearer(reviewer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].action", hasItem("AGENT_TASK_CREATED")))
                .andExpect(jsonPath("$[*].action", hasItem("AGENT_TASK_COMPLETED")));
    }

    @Test
    void agentCreationRequiresInvestigatorPermissionAssignmentAndInvestigatingStatus() throws Exception {
        String investigator = token("investigator");
        String reviewer = token("reviewer");
        String assetId = registerAsset(investigator);
        String draftCaseId = createCase(investigator, "状态限制", assetId);

        String request = """
                {"caseId":"%s","goal":"不应运行","stepBudget":3}
                """.formatted(draftCaseId);
        mockMvc.perform(post("/api/v1/agent-tasks")
                        .header("Authorization", bearer(investigator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AGENT_CASE_NOT_INVESTIGATING"));
        mockMvc.perform(post("/api/v1/agent-tasks")
                        .header("Authorization", bearer(reviewer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isForbidden());
    }

    @Test
    void pendingTaskCanBeCancelledButCannotRunAfterCancellation() throws Exception {
        String investigator = token("investigator");
        String caseId = investigatingCase(investigator, "取消任务");
        MvcResult created = mockMvc.perform(post("/api/v1/agent-tasks")
                        .header("Authorization", bearer(investigator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"caseId":"%s","goal":"稍后取消","stepBudget":3}
                                """.formatted(caseId)))
                .andExpect(status().isCreated())
                .andReturn();
        String taskId = JsonPath.read(created.getResponse().getContentAsString(), "$.task.id");

        mockMvc.perform(post("/api/v1/agent-tasks/{id}/cancel", taskId)
                        .header("Authorization", bearer(investigator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.status").value("CANCELLED"))
                .andExpect(jsonPath("$.steps[0].stepType").value("TASK_CANCELLED"));

        mockMvc.perform(post("/api/v1/agent-tasks/{id}/run", taskId)
                        .header("Authorization", bearer(investigator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AGENT_TASK_VERSION_CONFLICT"));
    }

    private String investigatingCase(String token, String title) throws Exception {
        String assetId = registerAsset(token);
        String caseId = createCase(token, title, assetId);
        transition(token, caseId, "READY", 0);
        transition(token, caseId, "INVESTIGATING", 1);
        return caseId;
    }

    private String registerAsset(String token) throws Exception {
        String sha = UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
        MvcResult result = mockMvc.perform(post("/api/v1/assets")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"originalFilename":"agent.png","contentType":"image/png","byteSize":2048,"sha256":"%s"}
                                """.formatted(sha)))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    private String createCase(String token, String title, String assetId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/cases")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"%s","description":"Agent test","priority":"NORMAL","assetIds":["%s"]}
                                """.formatted(title, assetId)))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.investigationCase.id");
    }

    private void transition(String token, String caseId, String target, int version) throws Exception {
        mockMvc.perform(post("/api/v1/cases/{id}/transitions", caseId)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"targetStatus":"%s","version":%d}
                                """.formatted(target, version)))
                .andExpect(status().isOk());
    }

    private String token(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tenantCode":"demo","username":"%s","password":"%s"}
                                """.formatted(username, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
