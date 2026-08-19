package com.originguard.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.originguard.agent.application.AgentExecutionContext;
import com.originguard.agent.application.AgentPlanValidator;
import com.originguard.agent.application.AgentPlanner;
import com.originguard.agent.application.LocalQwenPlanner;
import com.originguard.agent.application.SkillRegistry;
import com.originguard.identity.domain.CurrentActor;
import com.originguard.investigation.domain.CasePriority;
import com.originguard.investigation.domain.CaseStatus;
import com.originguard.investigation.domain.InvestigationCase;
import com.originguard.knowledge.application.KnowledgeRetriever;
import com.originguard.knowledge.domain.KnowledgeSearchResult;
import com.originguard.media.application.MediaAssetService;
import com.originguard.media.domain.MediaAsset;
import com.originguard.media.domain.MediaObject;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "ORIGINGUARD_REAL_QWEN_TEST", matches = "true")
class LocalQwenPlannerRealModelTests {
    @Test
    void localMultimodalModelProducesAWhitelistedValidatedPlan() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID assetId = UUID.randomUUID();
        MediaAsset asset = new MediaAsset(
                assetId, tenantId, "planner-test.png", "image/png", 0, "a".repeat(64),
                "STORED", UUID.randomUUID(), Instant.now());
        MediaObject object = new MediaObject(
                assetId, tenantId, tenantId + "/" + assetId, "image/png", 256, 192,
                "0".repeat(16), Map.of(), Instant.now());
        MediaAssetService media = mock(MediaAssetService.class);
        when(media.readStored(tenantId, assetId))
                .thenReturn(new MediaAssetService.StoredMedia(asset, object, image()));

        KnowledgeRetriever knowledge = mock(KnowledgeRetriever.class);
        when(knowledge.search(org.mockito.ArgumentMatchers.eq(tenantId),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq(5)))
                .thenReturn(List.of(new KnowledgeSearchResult(
                        UUID.randomUUID(), "AIGC media review guidance", "FORENSIC_GUIDE", 1,
                        UUID.randomUUID(), 0,
                        "Metadata and visual appearance are supporting facts, not a standalone verdict.",
                        0.8, 0.7, 0.78)));

        SkillRegistry skills = new SkillRegistry();
        LocalQwenPlanner planner = new LocalQwenPlanner(
                media, knowledge, skills,
                System.getenv().getOrDefault("QWEN_VL_BASE_URL", "http://127.0.0.1:8092"),
                "qwen3-vl-4b-instruct-q4-k-m", Duration.ofMinutes(5), 256);
        AgentPlanner.PlannerPlan plan = planner.plan(context(tenantId, asset),
                "Choose safe evidence collection skills for this image");

        new AgentPlanValidator(skills).validate(plan, 11);
        assertThat(plan.provider()).isEqualTo(LocalQwenPlanner.PROVIDER);
        assertThat(plan.skills()).extracting(AgentPlanner.SkillSelection::skillCode)
                .contains(
                        SkillRegistry.INTEGRITY_SKILL,
                        SkillRegistry.AIGC_DETECTION_SKILL,
                        SkillRegistry.RAG_SKILL);
        assertThat(plan.trace()).containsEntry("mode", "LOCAL_MULTIMODAL_LLM");
    }

    private AgentExecutionContext context(UUID tenantId, MediaAsset asset) {
        UUID userId = UUID.randomUUID();
        CurrentActor actor = new CurrentActor(
                userId, tenantId, "test", "investigator", "Investigator",
                Set.of("INVESTIGATOR"), Set.of("agent:run", "asset:read", "case:read", "knowledge:read"));
        InvestigationCase investigationCase = new InvestigationCase(
                UUID.randomUUID(), tenantId, "OG-TEST", "Suspicious social media image",
                "Check the supplied image without jumping to an AIGC verdict", CasePriority.NORMAL,
                CaseStatus.INVESTIGATING, userId, userId, null, 2, Instant.now(), Instant.now());
        return new AgentExecutionContext(actor, investigationCase, List.of(asset), 0);
    }

    private byte[] image() throws Exception {
        BufferedImage image = new BufferedImage(256, 192, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            graphics.setColor(new Color(30, 80, 160));
            graphics.fillRect(0, 0, 256, 192);
            graphics.setColor(Color.WHITE);
            graphics.drawString("OriginGuard planner test", 36, 96);
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }
}
