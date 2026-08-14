package com.originguard.investigation;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = "originguard.embedding.provider=deterministic")
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Testcontainers
class BusinessCoreIntegrationTests {
    private static final String PASSWORD = "OriginGuard@123";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcClient jdbcClient;

    @Test
    void investigatorCanRegisterAssetCreateCaseAndReadAuditTrail() throws Exception {
        String token = token("investigator");
        String assetId = registerAsset(token, randomSha());

        MvcResult created = createCase(token, "疑似 AIGC 人像", assetId);
        String caseId = JsonPath.read(created.getResponse().getContentAsString(), "$.investigationCase.id");

        mockMvc.perform(get("/api/v1/cases/{id}", caseId).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.investigationCase.status").value("DRAFT"))
                .andExpect(jsonPath("$.investigationCase.version").value(0))
                .andExpect(jsonPath("$.assets[0].id").value(assetId));

        mockMvc.perform(get("/api/v1/cases/{id}/audit", caseId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].action", hasItem("CASE_CREATED")));
    }

    @Test
    void duplicateAssetHashReturnsConflictWithoutCreatingAnotherRecord() throws Exception {
        String token = token("investigator");
        String sha = randomSha();
        registerAsset(token, sha);

        mockMvc.perform(post("/api/v1/assets")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(assetJson(sha)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ASSET_SHA256_CONFLICT"));
    }

    @Test
    void administratorAndReviewerCanReadButCannotMutateCases() throws Exception {
        String investigator = token("investigator");
        String assetId = registerAsset(investigator, randomSha());
        createCase(investigator, "只读权限验证", assetId);

        for (String username : new String[] {"admin", "reviewer"}) {
            String token = token(username);
            mockMvc.perform(get("/api/v1/cases").header("Authorization", bearer(token)))
                    .andExpect(status().isOk());
            mockMvc.perform(post("/api/v1/cases")
                            .header("Authorization", bearer(token))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(caseJson("禁止创建", assetId)))
                    .andExpect(status().isForbidden());
        }

        String admin = token("admin");
        mockMvc.perform(post("/api/v1/assets")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(assetJson(randomSha())))
                .andExpect(status().isForbidden());
    }

    @Test
    void tenantScopedQueriesHideCasesAndAssetsFromOtherTenant() throws Exception {
        UUID otherTenant = UUID.randomUUID();
        UUID otherUser = UUID.randomUUID();
        UUID otherAsset = UUID.randomUUID();
        UUID otherCase = UUID.randomUUID();
        jdbcClient.sql("INSERT INTO tenant(id, code, name) VALUES (:id, :code, :name)")
                .param("id", otherTenant)
                .param("code", "other-" + otherTenant.toString().substring(0, 8))
                .param("name", "Other Tenant")
                .update();
        jdbcClient.sql("""
                        INSERT INTO sys_user(id, tenant_id, username, display_name, password_hash)
                        VALUES (:id, :tenantId, 'outsider', 'Outsider', 'not-used')
                        """)
                .param("id", otherUser)
                .param("tenantId", otherTenant)
                .update();
        jdbcClient.sql("""
                        INSERT INTO media_asset(
                            id, tenant_id, original_filename, content_type, byte_size, sha256, created_by
                        ) VALUES (:id, :tenantId, 'other.png', 'image/png', 10, :sha256, :createdBy)
                        """)
                .param("id", otherAsset)
                .param("tenantId", otherTenant)
                .param("sha256", randomSha())
                .param("createdBy", otherUser)
                .update();
        jdbcClient.sql("""
                        INSERT INTO investigation_case(
                            id, tenant_id, case_number, title, created_by, assigned_investigator_id
                        ) VALUES (:id, :tenantId, :number, 'Other Case', :createdBy, :createdBy)
                        """)
                .param("id", otherCase)
                .param("tenantId", otherTenant)
                .param("number", "OG-OTHER-" + otherCase.toString().substring(0, 8))
                .param("createdBy", otherUser)
                .update();

        String token = token("admin");
        mockMvc.perform(get("/api/v1/cases/{id}", otherCase).header("Authorization", bearer(token)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CASE_NOT_FOUND"));
        mockMvc.perform(get("/api/v1/assets/{id}", otherAsset).header("Authorization", bearer(token)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ASSET_NOT_FOUND"));
    }

    @Test
    void staleCaseVersionIsRejected() throws Exception {
        String token = token("investigator");
        String assetId = registerAsset(token, randomSha());
        MvcResult created = createCase(token, "并发版本验证", assetId);
        String caseId = JsonPath.read(created.getResponse().getContentAsString(), "$.investigationCase.id");

        String update = """
                {"title":"第一次修改","description":"updated","priority":"HIGH","version":0}
                """;
        mockMvc.perform(patch("/api/v1/cases/{id}", caseId)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(update))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.investigationCase.version").value(1));

        mockMvc.perform(patch("/api/v1/cases/{id}", caseId)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(update))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CASE_VERSION_CONFLICT"));
    }

    @Test
    void stateMachineRequiresAssetAndRejectsSkippedTransitions() throws Exception {
        String token = token("investigator");
        MvcResult empty = createCase(token, "无资产案件", null);
        String emptyCaseId = JsonPath.read(empty.getResponse().getContentAsString(), "$.investigationCase.id");
        mockMvc.perform(post("/api/v1/cases/{id}/transitions", emptyCaseId)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transitionJson("READY", 0)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CASE_ASSET_REQUIRED"));

        String assetId = registerAsset(token, randomSha());
        MvcResult created = createCase(token, "状态机验证", assetId);
        String caseId = JsonPath.read(created.getResponse().getContentAsString(), "$.investigationCase.id");

        mockMvc.perform(post("/api/v1/cases/{id}/transitions", caseId)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transitionJson("WAITING_REVIEW", 0)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CASE_STATUS_CONFLICT"));

        transition(token, caseId, "READY", 0, 1);
        transition(token, caseId, "INVESTIGATING", 1, 2);
        mockMvc.perform(post("/api/v1/cases/{id}/transitions", caseId)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transitionJson("WAITING_REVIEW", 2)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CASE_EVIDENCE_REQUIRED"));
    }

    @Test
    void assignmentEvidenceAndIndependentReviewFormACompleteWorkflow() throws Exception {
        String investigator = token("investigator");
        String admin = token("admin");
        String reviewer = token("reviewer");
        String assetId = registerAsset(investigator, randomSha());
        MvcResult created = createCase(investigator, "M1.2 人工审核闭环", assetId);
        String caseId = JsonPath.read(created.getResponse().getContentAsString(), "$.investigationCase.id");

        transition(investigator, caseId, "READY", 0, 1);
        transition(investigator, caseId, "INVESTIGATING", 1, 2);

        UUID investigatorId = userId("investigator");
        UUID reviewerId = userId("reviewer");
        mockMvc.perform(post("/api/v1/cases/{id}/assignment", caseId)
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"investigatorId":"%s","reviewerId":"%s","version":2}
                                """.formatted(investigatorId, reviewerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignedInvestigatorId").value(investigatorId.toString()))
                .andExpect(jsonPath("$.assignedReviewerId").value(reviewerId.toString()))
                .andExpect(jsonPath("$.version").value(3));

        mockMvc.perform(post("/api/v1/cases/{id}/evidence", caseId)
                        .header("Authorization", bearer(investigator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "assetId":"%s",
                                  "title":"人物边缘存在异常融合",
                                  "observation":"发丝与背景交界区域出现重复纹理，且局部光照方向不一致。",
                                  "conclusion":"LIKELY_SYNTHETIC",
                                  "confidence":"HIGH",
                                  "version":3
                                }
                                """.formatted(assetId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.evidenceType").value("HUMAN_OBSERVATION"))
                .andExpect(jsonPath("$.conclusion").value("LIKELY_SYNTHETIC"));

        MvcResult evidenceResult = mockMvc.perform(get("/api/v1/cases/{id}/workflow", caseId)
                        .header("Authorization", bearer(investigator)))
                .andExpect(status().isOk())
                .andReturn();
        String evidenceId = JsonPath.read(
                evidenceResult.getResponse().getContentAsString(), "$.evidence[0].id");

        transition(investigator, caseId, "WAITING_REVIEW", 4, 5);

        MvcResult workflow = mockMvc.perform(get("/api/v1/cases/{id}/workflow", caseId)
                        .header("Authorization", bearer(reviewer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.evidence.length()").value(1))
                .andExpect(jsonPath("$.reviewTasks[0].status").value("PENDING"))
                .andExpect(jsonPath("$.reviewTasks[0].reviewerId").value(reviewerId.toString()))
                .andReturn();
        String taskId = JsonPath.read(workflow.getResponse().getContentAsString(), "$.reviewTasks[0].id");

        mockMvc.perform(post("/api/v1/cases/{caseId}/reviews/{taskId}/decision", caseId, taskId)
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewJson("APPROVED", "", evidenceId, 0, 5)))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/cases/{caseId}/reviews/{taskId}/decision", caseId, taskId)
                        .header("Authorization", bearer(reviewer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewJson("REJECTED", "", evidenceId, 0, 5)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REVIEW_REASON_REQUIRED"));

        mockMvc.perform(post("/api/v1/cases/{caseId}/reviews/{taskId}/decision", caseId, taskId)
                        .header("Authorization", bearer(reviewer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewJson("APPROVED", "人工复核同意该判断", evidenceId, 0, 5)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewTasks[0].status").value("APPROVED"))
                .andExpect(jsonPath("$.reviewTasks[0].citedEvidenceIds[0]").value(evidenceId));

        mockMvc.perform(get("/api/v1/cases/{id}", caseId).header("Authorization", bearer(reviewer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.investigationCase.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.investigationCase.version").value(6));
        mockMvc.perform(get("/api/v1/cases/{id}/audit", caseId)
                        .header("Authorization", bearer(reviewer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].action", hasItem("CASE_ASSIGNMENT_CHANGED")))
                .andExpect(jsonPath("$[*].action", hasItem("EVIDENCE_ADDED")))
                .andExpect(jsonPath("$[*].action", hasItem("REVIEW_TASK_CREATED")))
                .andExpect(jsonPath("$[*].action", hasItem("REVIEW_APPROVED")));
    }

    @Test
    void invalidMediaTypeAndUnknownStatusReturnStructuredValidationErrors() throws Exception {
        String token = token("investigator");
        mockMvc.perform(post("/api/v1/assets")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"originalFilename":"note.txt","contentType":"text/plain","byteSize":12,"sha256":"%s"}
                                """.formatted(randomSha())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        String assetId = registerAsset(token, randomSha());
        MvcResult created = createCase(token, "非法状态验证", assetId);
        String caseId = JsonPath.read(created.getResponse().getContentAsString(), "$.investigationCase.id");
        mockMvc.perform(post("/api/v1/cases/{id}/transitions", caseId)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transitionJson("UNKNOWN", 0)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    private String registerAsset(String token, String sha256) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/assets")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(assetJson(sha256)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tenantId").isNotEmpty())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    private MvcResult createCase(String token, String title, String assetId) throws Exception {
        return mockMvc.perform(post("/api/v1/cases")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(caseJson(title, assetId)))
                .andExpect(status().isCreated())
                .andReturn();
    }

    private void transition(String token, String caseId, String target, int version, int nextVersion)
            throws Exception {
        mockMvc.perform(post("/api/v1/cases/{id}/transitions", caseId)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transitionJson(target, version)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.investigationCase.status").value(target))
                .andExpect(jsonPath("$.investigationCase.version").value(nextVersion));
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

    private String assetJson(String sha256) {
        return """
                {"originalFilename":"sample.png","contentType":"image/png","byteSize":1024,"sha256":"%s"}
                """.formatted(sha256);
    }

    private String caseJson(String title, String assetId) {
        String assets = assetId == null ? "[]" : "[\"" + assetId + "\"]";
        return """
                {"title":"%s","description":"M1.1 integration test","priority":"NORMAL","assetIds":%s}
                """.formatted(title, assets);
    }

    private String transitionJson(String target, int version) {
        return """
                {"targetStatus":"%s","version":%d}
                """.formatted(target, version);
    }

    private String reviewJson(
            String decision, String reason, String evidenceId, int taskVersion, int caseVersion) {
        return """
                {"decision":"%s","reason":"%s","citedEvidenceIds":["%s"],"taskVersion":%d,"caseVersion":%d}
                """.formatted(decision, reason, evidenceId, taskVersion, caseVersion);
    }

    private UUID userId(String username) {
        return jdbcClient.sql("SELECT id FROM sys_user WHERE username = :username")
                .param("username", username)
                .query(UUID.class)
                .single();
    }

    private String randomSha() {
        return UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
