package com.originguard.agent;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;

import com.jayway.jsonpath.JsonPath;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.mock.web.MockMultipartFile;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = "originguard.embedding.provider=deterministic")
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Testcontainers
class AgentHarnessIntegrationTests {
    private static final String PASSWORD = "OriginGuard@123";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    @Container
    static final GenericContainer<?> MINIO = new GenericContainer<>("minio/minio:latest")
            .withEnv("MINIO_ROOT_USER", "originguard")
            .withEnv("MINIO_ROOT_PASSWORD", "change-me-now")
            .withCommand("server", "/data")
            .withExposedPorts(9000)
            .waitingFor(Wait.forHttp("/minio/health/live").forPort(9000));

    @DynamicPropertySource
    static void storageProperties(DynamicPropertyRegistry registry) {
        registry.add("originguard.storage.endpoint",
                () -> "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000));
        registry.add("originguard.storage.access-key", () -> "originguard");
        registry.add("originguard.storage.secret-key", () -> "change-me-now");
        registry.add("originguard.storage.bucket", () -> "agent-test-media");
    }

    @Autowired
    MockMvc mockMvc;

    @Test
    void fakePlannerRealMediaToolCheckpointAndTraceCompleteVerticalSlice() throws Exception {
        String admin = token("admin");
        String investigator = token("investigator");
        String reviewer = token("reviewer");
        publishKnowledge(admin);
        String caseId = investigatingCase(investigator, "Agent Harness 纵向切片");

        MvcResult created = mockMvc.perform(post("/api/v1/agent-tasks")
                        .header("Authorization", bearer(investigator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"caseId":"%s","goal":"运行确定性媒体分析与 RAG 流水线","stepBudget":9}
                                """.formatted(caseId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.task.status").value("PENDING"))
                .andExpect(jsonPath("$.task.remainingStepBudget").value(9))
                .andReturn();
        String taskId = JsonPath.read(created.getResponse().getContentAsString(), "$.task.id");

        MvcResult completed = mockMvc.perform(post("/api/v1/agent-tasks/{id}/run", taskId)
                        .header("Authorization", bearer(investigator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.status").value("COMPLETED"))
                .andExpect(jsonPath("$.task.selectedSkillCode").value("deterministic_media_rag_pipeline"))
                .andExpect(jsonPath("$.task.selectedSkillVersion").value("1.1.0"))
                .andExpect(jsonPath("$.task.remainingStepBudget").value(0))
                .andExpect(jsonPath("$.task.checkpointVersion").value(4))
                .andExpect(jsonPath("$.task.conclusion.verdict").value("INCONCLUSIVE"))
                .andExpect(jsonPath("$.steps.length()").value(21))
                .andExpect(jsonPath("$.steps[*].stepType", hasItem("PLAN_GENERATED")))
                .andExpect(jsonPath("$.steps[*].stepType", hasItem("PLAN_VALIDATED")))
                .andExpect(jsonPath("$.steps[1].output.provider").value("FAKE"))
                .andExpect(jsonPath("$.steps[1].output.selectedSkills.length()").value(4))
                .andExpect(jsonPath("$.steps[1].output.selectedSkills[0].reason").isNotEmpty())
                .andExpect(jsonPath("$.steps[*].stepType", hasItem("TOOL_CALLED")))
                .andExpect(jsonPath("$.steps[*].stepType", hasItem("CHECKPOINT_SAVED")))
                .andExpect(jsonPath("$.observations.length()").value(3))
                .andExpect(jsonPath("$.observations[0].evidenceType").value("FILE_INTEGRITY"))
                .andExpect(jsonPath("$.observations[0].payload.provider").value("ORIGINGUARD_INTERNAL"))
                .andExpect(jsonPath("$.observations[0].payload.allChecksPassed").value(true))
                .andExpect(jsonPath("$.observations[1].evidenceType").value("IMAGE_METADATA"))
                .andExpect(jsonPath("$.observations[1].payload.findings[0].width").value(4))
                .andExpect(jsonPath("$.observations[1].payload.findings[0].height").value(3))
                .andExpect(jsonPath("$.observations[2].evidenceType").value("PERCEPTUAL_SIMILARITY"))
                .andExpect(jsonPath("$.observations[2].payload.comparisonCount").value(0))
                .andExpect(jsonPath("$.knowledgeRetrievals.length()").value(1))
                .andExpect(jsonPath("$.knowledgeRetrievals[0].skillCode")
                        .value("retrieve_forensic_guidance"))
                .andExpect(jsonPath("$.knowledgeRetrievals[0].knowledgeAvailable").value(true))
                .andExpect(jsonPath("$.knowledgeRetrievals[0].citations[0].documentTitle")
                        .value("AIGC 媒体人工复核指引"))
                .andExpect(jsonPath("$.knowledgeRetrievals[0].citations[0].documentVersion").value(1))
                .andExpect(jsonPath("$.knowledgeRetrievals[0].citations[0].chunkId").isNotEmpty())
                .andExpect(jsonPath("$.checkpoints.length()").value(4))
                .andExpect(jsonPath("$.checkpoints[3].state.remainingStepBudget").value(1))
                .andExpect(jsonPath("$.checkpoints[3].state.observationIds.length()").value(3))
                .andExpect(jsonPath("$.checkpoints[3].state.knowledgeRetrievalIds.length()").value(1))
                .andReturn();
        String observationId = JsonPath.read(
                completed.getResponse().getContentAsString(), "$.observations[0].id");

        mockMvc.perform(post("/api/v1/cases/{id}/evidence/from-agent", caseId)
                        .header("Authorization", bearer(investigator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"observationId":"%s","version":2}
                                """.formatted(observationId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.evidenceType").value("AGENT_OBSERVATION"))
                .andExpect(jsonPath("$.sourceObservationId").value(observationId))
                .andExpect(jsonPath("$.conclusion").value("INCONCLUSIVE"))
                .andExpect(jsonPath("$.confidence").value("LOW"));

        mockMvc.perform(post("/api/v1/cases/{id}/evidence/from-agent", caseId)
                        .header("Authorization", bearer(investigator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"observationId":"%s","version":3}
                                """.formatted(observationId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AGENT_OBSERVATION_ALREADY_INCLUDED"));

        mockMvc.perform(get("/api/v1/cases/{id}/workflow", caseId)
                        .header("Authorization", bearer(reviewer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.evidence[0].evidenceType").value("AGENT_OBSERVATION"))
                .andExpect(jsonPath("$.agentEvidenceCandidates.length()").value(3))
                .andExpect(jsonPath("$.agentEvidenceCandidates[0].promotedEvidenceId").isNotEmpty());

        mockMvc.perform(get("/api/v1/agent-tasks/{id}", taskId)
                        .header("Authorization", bearer(reviewer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.status").value("COMPLETED"))
                .andExpect(jsonPath("$.steps.length()").value(21));

        mockMvc.perform(get("/api/v1/cases/{id}/audit", caseId)
                        .header("Authorization", bearer(reviewer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].action", hasItem("AGENT_TASK_CREATED")))
                .andExpect(jsonPath("$[*].action", hasItem("AGENT_TASK_COMPLETED")))
                .andExpect(jsonPath("$[*].action", hasItem("AGENT_OBSERVATION_INCLUDED")));
    }

    @Test
    void perceptualSimilaritySkillComparesEveryAssetPairDeterministically() throws Exception {
        String investigator = token("investigator");
        String leftAssetId = uploadAsset(investigator, png());
        String rightAssetId = uploadAsset(investigator, png());
        String caseId = createCase(investigator, "感知哈希比较", leftAssetId, rightAssetId);
        transition(investigator, caseId, "READY", 0);
        transition(investigator, caseId, "INVESTIGATING", 1);

        MvcResult created = mockMvc.perform(post("/api/v1/agent-tasks")
                        .header("Authorization", bearer(investigator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"caseId":"%s","goal":"比较案件图片相似度","stepBudget":9}
                                """.formatted(caseId)))
                .andExpect(status().isCreated())
                .andReturn();
        String taskId = JsonPath.read(created.getResponse().getContentAsString(), "$.task.id");

        mockMvc.perform(post("/api/v1/agent-tasks/{id}/run", taskId)
                        .header("Authorization", bearer(investigator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.status").value("COMPLETED"))
                .andExpect(jsonPath("$.observations[2].evidenceType").value("PERCEPTUAL_SIMILARITY"))
                .andExpect(jsonPath("$.observations[2].payload.assetCount").value(2))
                .andExpect(jsonPath("$.observations[2].payload.comparisonCount").value(1))
                .andExpect(jsonPath("$.observations[2].payload.comparisons[0].hammingDistance").value(0))
                .andExpect(jsonPath("$.observations[2].payload.comparisons[0].classification")
                        .value("IDENTICAL_DHASH"));
    }

    @Test
    void ragDebugSearchAndEvaluationBaselineExposeRankingAndSafetyChecks() throws Exception {
        String admin = token("admin");
        publishKnowledge(admin);

        MvcResult debug = mockMvc.perform(post("/api/v1/rag/debug-search")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"query":"EXIF 缺失能否单独证明图片由 AI 生成","topK":5}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.embeddingProvider").value("LOCAL_DETERMINISTIC_HASH_V1"))
                .andExpect(jsonPath("$.results[0].documentId").isNotEmpty())
                .andExpect(jsonPath("$.results[0].semanticScore").isNumber())
                .andExpect(jsonPath("$.results[0].keywordScore").isNumber())
                .andExpect(jsonPath("$.results[0].hybridScore").isNumber())
                .andReturn();
        String documentId = JsonPath.read(debug.getResponse().getContentAsString(), "$.results[0].documentId");
        String chunkId = JsonPath.read(debug.getResponse().getContentAsString(), "$.results[0].chunkId");

        mockMvc.perform(post("/api/v1/rag/evaluation-cases")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name":"EXIF 缺失判断",
                                  "query":"EXIF 缺失能否单独证明图片由 AI 生成",
                                  "expectedDocumentId":"%s",
                                  "expectedChunkId":"%s"
                                }
                                """.formatted(documentId, chunkId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.expectedDocumentId").value(documentId))
                .andExpect(jsonPath("$.expectedChunkId").value(chunkId));

        mockMvc.perform(post("/api/v1/rag/evaluation-runs")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"topK\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caseCount").value(1))
                .andExpect(jsonPath("$.recallAtK").value(1.0))
                .andExpect(jsonPath("$.mrr").value(1.0))
                .andExpect(jsonPath("$.tenantIsolationPassed").value(true))
                .andExpect(jsonPath("$.draftExclusionPassed").value(true))
                .andExpect(jsonPath("$.citationIntegrityPassed").value(true))
                .andExpect(jsonPath("$.caseResults[0].firstRelevantRank").value(1));
    }

    @Test
    void agentCreationRequiresInvestigatorPermissionAssignmentAndInvestigatingStatus() throws Exception {
        String investigator = token("investigator");
        String reviewer = token("reviewer");
        String assetId = registerAsset(investigator);
        String draftCaseId = createCase(investigator, "状态限制", assetId);

        String request = """
                {"caseId":"%s","goal":"不应运行","stepBudget":9}
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
                                {"caseId":"%s","goal":"稍后取消","stepBudget":9}
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

    @Test
    void uploadRejectsSpoofedBytesAndAuthorizedReaderGetsStoredContent() throws Exception {
        String investigator = token("investigator");
        MockMultipartFile spoofed = new MockMultipartFile(
                "file", "spoofed.png", "image/png", "not-an-image".getBytes());
        String spoofedSha = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(spoofed.getBytes()));
        mockMvc.perform(multipart("/api/v1/assets/upload")
                        .header("Authorization", bearer(investigator))
                        .file(spoofed)
                        .param("sha256", spoofedSha))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MEDIA_CONTENT_INVALID"));

        byte[] content = png();
        String assetId = uploadAsset(investigator, content);
        mockMvc.perform(get("/api/v1/assets/{id}/content", assetId)
                        .header("Authorization", bearer(investigator)))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(content().bytes(content));
    }

    private String investigatingCase(String token, String title) throws Exception {
        String assetId = registerAsset(token);
        String caseId = createCase(token, title, assetId);
        transition(token, caseId, "READY", 0);
        transition(token, caseId, "INVESTIGATING", 1);
        return caseId;
    }

    private String publishKnowledge(String token) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/knowledge-documents")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title":"AIGC 媒体人工复核指引",
                                  "documentType":"FORENSIC_GUIDE",
                                  "content":"# 复核原则\\n\\n文件完整性、EXIF 元数据和感知相似度只能作为辅助事实。审核员应结合来源链、模型检测结果与上下文进行判断，不得只凭单项指标认定 AI 生成。",
                                  "version":0
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andReturn();
        String documentId = JsonPath.read(created.getResponse().getContentAsString(), "$.id");
        mockMvc.perform(post("/api/v1/knowledge-documents/{id}/publish", documentId)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.document.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.document.publishedVersion").value(1))
                .andExpect(jsonPath("$.chunkCount").value(1))
                .andExpect(jsonPath("$.embeddingProvider").value("LOCAL_DETERMINISTIC_HASH_V1"));
        return documentId;
    }

    private String registerAsset(String token) throws Exception {
        return uploadAsset(token, png());
    }

    private String uploadAsset(String token, byte[] content) throws Exception {
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        MockMultipartFile file = new MockMultipartFile(
                "file", "agent.png", "image/png", content);
        MvcResult result = mockMvc.perform(multipart("/api/v1/assets/upload")
                        .header("Authorization", bearer(token))
                        .file(file)
                        .param("sha256", sha))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.storageStatus").value("STORED"))
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    private byte[] png() throws Exception {
        BufferedImage image = new BufferedImage(4, 3, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            graphics.setColor(new Color(UUID.randomUUID().hashCode() & 0x00ffffff));
            graphics.fillRect(0, 0, 4, 3);
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    private String createCase(String token, String title, String assetId) throws Exception {
        return createCase(token, title, new String[] {assetId});
    }

    private String createCase(String token, String title, String... assetIds) throws Exception {
        String assetsJson = java.util.Arrays.stream(assetIds)
                .map(id -> "\"" + id + "\"")
                .collect(java.util.stream.Collectors.joining(","));
        MvcResult result = mockMvc.perform(post("/api/v1/cases")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"%s","description":"Agent test","priority":"NORMAL","assetIds":[%s]}
                                """.formatted(title, assetsJson)))
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
