package com.originguard.agent.application;

import com.originguard.media.application.MediaAssetService;
import com.originguard.media.domain.MediaAsset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
    private final ForensicModelRegistry modelRegistry;

    public ModelApiAigcDetectionTool(
            MediaAssetService mediaAssetService,
            AgentArtifactStorage artifactStorage,
            AigcResultExplainer resultExplainer,
            AigcEvidenceFusion evidenceFusion,
            ForensicModelRegistry modelRegistry) {
        this.mediaAssetService = mediaAssetService;
        this.artifactStorage = artifactStorage;
        this.resultExplainer = resultExplainer;
        this.evidenceFusion = evidenceFusion;
        this.modelRegistry = modelRegistry;
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
            Map<String, Object> mediaTypeContext = objectMap(mediaTypeContexts.get(asset.id().toString()));
            if (mediaTypeContext.isEmpty()) mediaTypeContext = unavailableMediaTypeContext();
            String mediaType = String.valueOf(mediaTypeContext.getOrDefault("mediaType", "UNKNOWN"));
            ForensicModelRegistry.ModelRoute route =
                    modelRegistry.route(asset.contentType(), mediaType, "AIGC_DETECTION");
            ForensicModelAdapter adapter = modelRegistry.requireAdapter(route.selected().code());
            Map<String, Object> detection = adapter.analyze(stored.content(), asset.contentType());
            Map<String, Object> quality = objectMap(detection.get("qualityAssessment"));
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
            finding.put("modelRouting", route.toMap());
            finding.put("fusion", fusion);
            Map<String, Object> explanationInput = new LinkedHashMap<>(detection);
            explanationInput.put("mediaTypeContext", mediaTypeContext);
            explanationInput.put("fusion", fusion);
            finding.put("explanation", resultExplainer.explain(
                    asset.originalFilename(), stored.content(), attentionOverlay, explanationInput));
            finding.put("forensicObservation", normalizedObservation(
                    asset, route, detection, fusion, quality, finding.get("attentionArtifact")));
            findings.add(Map.copyOf(finding));
        }
        if (findings.isEmpty()) {
            throw new IllegalStateException("AIGC detection requires at least one linked image asset");
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
        output.put("capabilityCatalog", modelRegistry.catalog());
        output.put("limitations", first.getOrDefault("limitations", List.of()));
        return Map.copyOf(output);
    }

    private Map<String, Object> normalizedObservation(
            MediaAsset asset,
            ForensicModelRegistry.ModelRoute route,
            Map<String, Object> detection,
            Map<String, Object> fusion,
            Map<String, Object> quality,
            Object visualization) {
        Map<String, Object> probabilities = new LinkedHashMap<>();
        probabilities.put("synthetic", detection.getOrDefault("syntheticProbability", 0.0));
        probabilities.put("authentic", detection.getOrDefault("authenticProbability", 0.0));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", "1.0.0");
        result.put("capability", route.selected().toMap(true));
        result.put("assetId", asset.id().toString());
        result.put("mediaKind", route.mediaKind());
        result.put("mediaType", route.mediaType());
        result.put("status", "SUCCEEDED");
        result.put("applicability", route.unavailableRecommended().isEmpty() ? "DIRECT" : "DEGRADED");
        result.put("probabilities", Map.copyOf(probabilities));
        result.put("verdict", fusion.getOrDefault("verdict", detection.getOrDefault("classification", "INCONCLUSIVE")));
        result.put("confidence", fusion.getOrDefault("confidence", "LOW"));
        result.put("quality", quality);
        result.put("visualization", visualization == null ? Map.of() : visualization);
        result.put("limitations", detection.getOrDefault("limitations", List.of()));
        result.put("routing", route.toMap());
        return Map.copyOf(result);
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
