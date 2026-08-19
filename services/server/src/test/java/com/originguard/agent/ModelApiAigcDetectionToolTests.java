package com.originguard.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.originguard.agent.application.AgentExecutionContext;
import com.originguard.agent.application.AgentArtifactStorage;
import com.originguard.agent.application.AigcEvidenceFusion;
import com.originguard.agent.application.AigcResultExplainer;
import com.originguard.agent.application.ModelApiAigcDetectionTool;
import com.originguard.identity.domain.CurrentActor;
import com.originguard.investigation.domain.CasePriority;
import com.originguard.investigation.domain.CaseStatus;
import com.originguard.investigation.domain.InvestigationCase;
import com.originguard.media.application.MediaAssetService;
import com.originguard.media.domain.MediaAsset;
import com.originguard.media.domain.MediaObject;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ModelApiAigcDetectionToolTests {
    @Test
    void chineseFilenameDoesNotBecomeAnInvalidHttpHeader() throws Exception {
        AtomicReference<String> receivedContentType = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/aigc/detect", exchange -> {
            receivedContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            exchange.getRequestBody().readAllBytes();
            byte[] response = responseJson().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            UUID tenantId = UUID.randomUUID();
            UUID assetId = UUID.randomUUID();
            MediaAsset asset = new MediaAsset(
                    assetId, tenantId, "微信图片_20241228130211.jpg", "image/jpeg", 3,
                    "a".repeat(64), "STORED", UUID.randomUUID(), Instant.now());
            MediaObject mediaObject = new MediaObject(
                    assetId, tenantId, tenantId + "/" + assetId, "image/jpeg", 1, 1,
                    "0".repeat(16), Map.of(), Instant.now());
            MediaAssetService media = mock(MediaAssetService.class);
            AgentArtifactStorage artifacts = mock(AgentArtifactStorage.class);
            AigcResultExplainer explainer = mock(AigcResultExplainer.class);
            when(media.readStored(tenantId, assetId))
                    .thenReturn(new MediaAssetService.StoredMedia(asset, mediaObject, new byte[] {1, 2, 3}));
            when(artifacts.storeAttentionOverlay(org.mockito.ArgumentMatchers.eq(tenantId),
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(assetId),
                    org.mockito.ArgumentMatchers.any()))
                    .thenReturn(new AgentArtifactStorage.StoredArtifact(
                            UUID.randomUUID(), "AIDE_ATTENTION_OVERLAY", "image/png", 7, "b".repeat(64)));
            when(explainer.explain(org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any()))
                    .thenReturn(Map.of("source", "DETERMINISTIC_TEMPLATE", "summary", "测试解释"));
            ModelApiAigcDetectionTool tool = new ModelApiAigcDetectionTool(
                    media, artifacts, explainer, new AigcEvidenceFusion(),
                    "http://127.0.0.1:" + server.getAddress().getPort(), Duration.ofSeconds(5));

            Map<String, Object> output = tool.execute(
                    context(tenantId, asset), Map.of(
                            "agentTaskId", UUID.randomUUID().toString(),
                            "mediaTypeContexts", Map.of(assetId.toString(), mediaType("PHOTOGRAPH", "摄影图像"))));

            assertThat(receivedContentType.get()).isEqualTo("image/jpeg");
            assertThat(output).containsEntry("provider", "AIDE_ICLR_2025_OFFICIAL");
            assertThat(output).containsEntry("overallClassification", "LIKELY_SYNTHETIC");
            assertThat(output).containsEntry("overallVerdict", "LIKELY_SYNTHETIC");
        } finally {
            server.stop(0);
        }
    }

    private Map<String, Object> mediaType(String code, String label) {
        return Map.of(
                "provider", "OPENAI_CLIP", "role", "MEDIA_TYPE_CONTEXT", "status", "AVAILABLE",
                "mediaType", code, "mediaTypeLabel", label, "mediaTypeScore", 0.9);
    }

    private AgentExecutionContext context(UUID tenantId, MediaAsset asset) {
        UUID userId = UUID.randomUUID();
        CurrentActor actor = new CurrentActor(
                userId, tenantId, "demo", "investigator", "Investigator",
                Set.of("INVESTIGATOR"), Set.of("agent:run", "asset:read", "case:read"));
        InvestigationCase investigationCase = new InvestigationCase(
                UUID.randomUUID(), tenantId, "OG-TEST", "中文文件名回归测试", "AIDE",
                CasePriority.NORMAL, CaseStatus.INVESTIGATING, userId, userId, null,
                2, Instant.now(), Instant.now());
        return new AgentExecutionContext(actor, investigationCase, List.of(asset), 0);
    }

    private String responseJson() {
        return """
                {
                  "provider":"AIDE_ICLR_2025_OFFICIAL",
                  "model":"AIDE GenImage train",
                  "modelVersion":"test",
                  "checkpointSha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                  "device":"cpu",
                  "precision":"float32",
                  "syntheticProbability":0.91,
                  "authenticProbability":0.09,
                  "classification":"LIKELY_SYNTHETIC",
                  "syntheticThreshold":0.8,
                  "authenticThreshold":0.2,
                  "width":64,
                  "height":64,
                  "processingMilliseconds":10,
                  "qualityAssessment":{
                    "status":"PASS","modelEligible":true,"qualityScore":100,
                    "width":64,"height":64,"minDimension":64,"aspectRatio":1.0,
                    "sharpnessVariance":100.0,"grayscaleEntropy":7.0,"issues":[]
                  },
                  "attentionMethod":"Grad-CAM test",
                  "attentionTarget":"LIKELY_SYNTHETIC",
                  "attentionOverlayPngBase64":"aGVhdG1hcA==",
                  "limitations":[]
                }
                """;
    }
}
