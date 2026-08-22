package com.originguard.agent.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.originguard.media.application.MediaAssetService;
import com.originguard.media.domain.MediaAsset;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "originguard.agent.aigc-detector.provider",
        havingValue = "model-api",
        matchIfMissing = true)
public class ModelApiAigcDetectionTool implements AgentTool {
    private final MediaAssetService mediaAssetService;
    private final AgentArtifactStorage artifactStorage;
    private final AigcResultExplainer resultExplainer;
    private final AigcEvidenceFusion evidenceFusion;
    private final ObjectMapper objectMapper;
    private final HttpClient client;
    private final URI endpoint;
    private final Duration timeout;

    public ModelApiAigcDetectionTool(
            MediaAssetService mediaAssetService,
            AgentArtifactStorage artifactStorage,
            AigcResultExplainer resultExplainer,
            AigcEvidenceFusion evidenceFusion,
            @Value("${originguard.agent.aigc-detector.base-url:http://127.0.0.1:8090}") String baseUrl,
            @Value("${originguard.agent.aigc-detector.timeout:PT10M}") Duration timeout) {
        this.mediaAssetService = mediaAssetService;
        this.artifactStorage = artifactStorage;
        this.resultExplainer = resultExplainer;
        this.evidenceFusion = evidenceFusion;
        this.objectMapper = new ObjectMapper();
        this.timeout = timeout;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        this.endpoint = URI.create(baseUrl.replaceAll("/+$", "") + "/v1/aigc/detect");
    }

    @Override
    public String code() {
        return AigcDetectionTool.CODE;
    }

    @Override
    public Map<String, Object> execute(AgentExecutionContext context, Map<String, Object> input) {
        UUID taskId = UUID.fromString(String.valueOf(input.get("agentTaskId")));
        Map<String, Object> mediaTypeContexts = objectMap(input.get("mediaTypeContexts"));
        List<Map<String, Object>> findings = new ArrayList<>();
        for (MediaAsset asset : context.assets()) {
            if (!asset.contentType().startsWith("image/")) continue;
            MediaAssetService.StoredMedia stored =
                    mediaAssetService.readStored(context.actor().tenantId(), asset.id());
            Map<String, Object> detection = detect(stored.content(), asset.contentType());
            Map<String, Object> quality = objectMap(detection.get("qualityAssessment"));
            Map<String, Object> mediaTypeContext = objectMap(mediaTypeContexts.get(asset.id().toString()));
            if (mediaTypeContext.isEmpty()) mediaTypeContext = unavailableMediaTypeContext();
            Map<String, Object> fusion = evidenceFusion.fuse(detection, mediaTypeContext, quality);
            byte[] attentionOverlay = detection.containsKey("attentionOverlayPngBase64")
                    ? Base64.getDecoder().decode(String.valueOf(detection.get("attentionOverlayPngBase64")))
                    : new byte[0];
            Map<String, Object> finding = new LinkedHashMap<>();
            finding.put("assetId", asset.id().toString());
            finding.put("filename", asset.originalFilename());
            detection.forEach((key, value) -> {
                if (!"attentionOverlayPngBase64".equals(key)) finding.put(key, value);
            });
            if (attentionOverlay.length > 0) {
                AgentArtifactStorage.StoredArtifact artifact = artifactStorage.storeAttentionOverlay(
                        context.actor().tenantId(), taskId, asset.id(), attentionOverlay);
                finding.put("attentionArtifact", Map.of(
                        "artifactId", artifact.artifactId().toString(),
                        "kind", artifact.kind(),
                        "contentType", artifact.contentType(),
                        "byteSize", artifact.byteSize(),
                        "sha256", artifact.sha256()));
            }
            finding.put("mediaTypeContext", mediaTypeContext);
            finding.put("fusion", fusion);
            Map<String, Object> explanationInput = new LinkedHashMap<>(detection);
            explanationInput.put("mediaTypeContext", mediaTypeContext);
            explanationInput.put("fusion", fusion);
            finding.put("explanation", resultExplainer.explain(
                    asset.originalFilename(), stored.content(), attentionOverlay, explanationInput));
            findings.add(Map.copyOf(finding));
        }
        if (findings.isEmpty()) {
            throw new IllegalStateException("AIDE requires at least one linked image asset");
        }
        Map<String, Object> first = findings.getFirst();
        String overallClassification = aggregateClassification(findings);
        String deterministicVerdict = aggregateVerdict(findings);
        Map<String, Object> agentAssessment = resultExplainer.synthesize(findings, deterministicVerdict);
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("provider", first.get("provider"));
        output.put("toolVersion", "4.0.0");
        output.put("model", first.get("model"));
        output.put("modelVersion", first.get("modelVersion"));
        output.put("checkpointSha256", first.get("checkpointSha256"));
        output.put("device", first.get("device"));
        output.put("analyzedImageCount", findings.size());
        output.put("overallClassification", overallClassification);
        output.put("overallVerdict", agentAssessment.getOrDefault("verdict", deterministicVerdict));
        output.put("agentAssessment", agentAssessment);
        output.put("maximumSyntheticProbability", findings.stream()
                .map(finding -> finding.get("syntheticProbability"))
                .filter(Number.class::isInstance)
                .map(Number.class::cast)
                .mapToDouble(Number::doubleValue)
                .max().orElse(0.0));
        output.put("findings", List.copyOf(findings));
        output.put("limitations", first.getOrDefault("limitations", List.of()));
        return Map.copyOf(output);
    }

    private Map<String, Object> detect(byte[] content, String contentType) {
        try {
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(timeout)
                    .header("Content-Type", contentType)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(content))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException(
                        "AIDE model API returned HTTP " + response.statusCode() + ": " + response.body());
            }
            return objectMapper.readValue(response.body(), new TypeReference<>() {});
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("AIDE request was interrupted", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("AIDE model API is unavailable at " + endpoint, exception);
        }
    }

    private String aggregateClassification(List<Map<String, Object>> findings) {
        boolean synthetic = findings.stream()
                .anyMatch(finding -> "LIKELY_SYNTHETIC".equals(finding.get("classification")));
        boolean authentic = findings.stream()
                .anyMatch(finding -> "LIKELY_AUTHENTIC".equals(finding.get("classification")));
        boolean inconclusive = findings.stream()
                .anyMatch(finding -> "INCONCLUSIVE".equals(finding.get("classification")));
        if (synthetic && !authentic && !inconclusive) return "LIKELY_SYNTHETIC";
        if (authentic && !synthetic && !inconclusive) return "LIKELY_AUTHENTIC";
        return "INCONCLUSIVE";
    }

    private String aggregateVerdict(List<Map<String, Object>> findings) {
        List<String> verdicts = findings.stream()
                .map(finding -> objectMap(finding.get("fusion")))
                .map(fusion -> String.valueOf(fusion.getOrDefault("verdict", "INCONCLUSIVE")))
                .toList();
        if (verdicts.contains("CONFLICTING_EVIDENCE")) return "CONFLICTING_EVIDENCE";
        if (verdicts.stream().allMatch("UNSUPPORTED_INPUT"::equals)) return "UNSUPPORTED_INPUT";
        if (verdicts.stream().allMatch("LIKELY_SYNTHETIC"::equals)) return "LIKELY_SYNTHETIC";
        if (verdicts.stream().allMatch("LIKELY_AUTHENTIC"::equals)) return "LIKELY_AUTHENTIC";
        return "INCONCLUSIVE";
    }

    private Map<String, Object> objectMap(Object value) {
        if (!(value instanceof Map<?, ?> raw)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, item) -> result.put(String.valueOf(key), item));
        return Map.copyOf(result);
    }

    private Map<String, Object> unavailableMediaTypeContext() {
        return Map.of(
                "provider", "NONE",
                "status", "UNAVAILABLE",
                "mediaType", "UNKNOWN",
                "mediaTypeLabel", "类型不明确",
                "limitations", List.of("规划前未获得 CLIP 媒体类型上下文"));
    }
}
