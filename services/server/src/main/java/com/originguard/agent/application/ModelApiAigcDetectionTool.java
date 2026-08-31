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
    private final AgentExecutionEventRecorder eventRecorder;
    private final ForensicResultCache resultCache;

    public ModelApiAigcDetectionTool(
            MediaAssetService mediaAssetService,
            AgentArtifactStorage artifactStorage,
            AigcResultExplainer resultExplainer,
            AigcEvidenceFusion evidenceFusion,
            ForensicModelRegistry modelRegistry,
            AgentExecutionEventRecorder eventRecorder,
            ForensicResultCache resultCache) {
        this.mediaAssetService = mediaAssetService;
        this.artifactStorage = artifactStorage;
        this.resultExplainer = resultExplainer;
        this.evidenceFusion = evidenceFusion;
        this.modelRegistry = modelRegistry;
        this.eventRecorder = eventRecorder;
        this.resultCache = resultCache;
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
            eventRecorder.recordAigc(
                    context,
                    taskId,
                    "MODEL_ROUTING_STARTED",
                    Map.of("assetId", asset.id().toString(), "mediaType", mediaType),
                    Map.of("message", "正在根据媒体类型与能力适用范围选择检测模型"));
            ForensicModelRegistry.ModelRoute route =
                    modelRegistry.route(asset.contentType(), mediaType, "AIGC_DETECTION");
            ForensicModelAdapter adapter = modelRegistry.requireAdapter(route.selected().code());
            eventRecorder.recordAigc(
                    context,
                    taskId,
                    "PRIMARY_MODEL_STARTED",
                    Map.of("assetId", asset.id().toString(), "capabilityCode", route.selected().code()),
                    Map.of(
                            "message", "已选择“" + route.selected().displayName() + "”，正在分析原始图片",
                            "capabilityName", route.selected().displayName(),
                            "mediaType", mediaType,
                            "degraded", !route.unavailableRecommended().isEmpty()));
            String cacheIdentity = route.selected().code() + ":" + asset.sha256();
            var cachedDetection = resultCache.get("aigc-primary", cacheIdentity);
            Map<String, Object> detection = cachedDetection.orElseGet(() -> {
                Map<String, Object> computed = adapter.analyze(stored.content(), asset.contentType());
                resultCache.put("aigc-primary", cacheIdentity, computed);
                return computed;
            });
            detection = new LinkedHashMap<>(detection);
            detection.put("cacheHit", cachedDetection.isPresent());
            eventRecorder.recordAigc(
                    context,
                    taskId,
                    "PRIMARY_MODEL_COMPLETED",
                    Map.of("assetId", asset.id().toString(), "capabilityCode", route.selected().code()),
                    compactDetectionEvent(route, detection));
            Map<String, Object> quality = objectMap(detection.get("qualityAssessment"));
            Map<String, Object> crossDomainVerification = runCrossDomainVerification(
                    context, taskId, asset, stored.content(), route);
            Map<String, Object> secondaryVerification = runDiffusionVerificationIfUseful(
                    context, taskId, asset, stored.content(), mediaType, detection);
            eventRecorder.recordAigc(
                    context,
                    taskId,
                    "EVIDENCE_FUSION_STARTED",
                    Map.of("assetId", asset.id().toString()),
                    Map.of("message", "正在合并领域模型、通用模型、媒体类型、图像质量与扩散复核信号"));
            Map<String, Object> fusion = evidenceFusion.fuse(
                    detection, mediaTypeContext, quality, secondaryVerification, crossDomainVerification);
            eventRecorder.recordAigc(
                    context,
                    taskId,
                    "EVIDENCE_FUSED",
                    Map.of("assetId", asset.id().toString()),
                    Map.of(
                            "message", "多源检测信号已完成融合，准备生成可解释说明",
                            "verdict", String.valueOf(fusion.getOrDefault("verdict", "INCONCLUSIVE")),
                            "confidence", String.valueOf(fusion.getOrDefault("confidence", "LOW")),
                            "decisionReady", Boolean.TRUE.equals(fusion.get("decisionReady"))));
            String visualizationBase64 = String.valueOf(detection.getOrDefault(
                    "localizationOverlayPngBase64", detection.getOrDefault("attentionOverlayPngBase64", "")));
            byte[] attentionOverlay = visualizationBase64.isBlank()
                    ? new byte[0] : Base64.getDecoder().decode(visualizationBase64);
            Map<String, Object> finding = new LinkedHashMap<>();
            finding.put("assetId", asset.id().toString());
            finding.put("filename", asset.originalFilename());
            detection.forEach((key, value) -> {
                if (!"attentionOverlayPngBase64".equals(key) && !"localizationOverlayPngBase64".equals(key)) {
                    finding.put(key, value);
                }
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
            finding.put("crossDomainVerification", crossDomainVerification);
            finding.put("secondaryVerification", secondaryVerification);
            finding.put("fusion", fusion);
            Map<String, Object> explanationInput = new LinkedHashMap<>(detection);
            explanationInput.put("mediaTypeContext", mediaTypeContext);
            explanationInput.put("fusion", fusion);
            explanationInput.put("crossDomainVerification", crossDomainVerification);
            explanationInput.put("secondaryVerification", secondaryVerification);
            eventRecorder.recordAigc(
                    context,
                    taskId,
                    "RESULT_EXPLANATION_STARTED",
                    Map.of("assetId", asset.id().toString()),
                    Map.of("message", "LLM 正在把模型信号整理为可核验的中文说明"));
            Map<String, Object> explanation = resultExplainer.explain(
                    asset.originalFilename(), stored.content(), attentionOverlay, explanationInput);
            finding.put("explanation", explanation);
            eventRecorder.recordAigc(
                    context,
                    taskId,
                    "RESULT_EXPLAINED",
                    Map.of("assetId", asset.id().toString()),
                    Map.of(
                            "message", "中文结果说明已生成",
                            "summary", String.valueOf(explanation.getOrDefault("summary", "模型结果解释已完成")),
                            "source", String.valueOf(explanation.getOrDefault("source", "DETERMINISTIC_TEMPLATE"))));
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

    private Map<String, Object> runCrossDomainVerification(
            AgentExecutionContext context,
            UUID taskId,
            MediaAsset asset,
            byte[] content,
            ForensicModelRegistry.ModelRoute route) {
        if (!"ANIME_MANGA".equals(route.mediaType())) return Map.of("status", "NOT_REQUIRED");
        var candidate = route.availableCandidates().stream()
                .filter(capability -> !capability.code().equals(route.selected().code()))
                .filter(capability -> capability.mediaTypes().contains(ForensicModelCapability.ANY_MEDIA_TYPE))
                .findFirst();
        if (candidate.isEmpty()) {
            return Map.of("status", "UNAVAILABLE", "reason", "未找到可用的跨域通用模型");
        }
        ForensicModelCapability capability = candidate.get();
        eventRecorder.recordAigc(
                context,
                taskId,
                "CROSS_DOMAIN_MODEL_STARTED",
                Map.of("assetId", asset.id().toString(), "capabilityCode", capability.code()),
                Map.of("message", "正在使用通用生成内容鉴别模型交叉复核动漫专用模型"));
        try {
            ForensicModelAdapter adapter = modelRegistry.requireAdapter(capability.code());
            String cacheIdentity = capability.code() + ":" + asset.sha256();
            var cached = resultCache.get("aigc-cross-domain", cacheIdentity);
            Map<String, Object> result = new LinkedHashMap<>(cached.orElseGet(() -> {
                Map<String, Object> computed = adapter.analyze(content, asset.contentType());
                resultCache.put("aigc-cross-domain", cacheIdentity, computed);
                return computed;
            }));
            result.put("status", "SUCCEEDED");
            result.put("cacheHit", cached.isPresent());
            result.put("capability", capability.toMap(true));
            eventRecorder.recordAigc(
                    context,
                    taskId,
                    "CROSS_DOMAIN_MODEL_COMPLETED",
                    Map.of("assetId", asset.id().toString(), "capabilityCode", capability.code()),
                    Map.of(
                            "message", "通用模型交叉复核已完成",
                            "classification", result.getOrDefault("classification", "INCONCLUSIVE"),
                            "syntheticProbability", result.getOrDefault("syntheticProbability", "UNAVAILABLE")));
            return Map.copyOf(result);
        } catch (RuntimeException exception) {
            eventRecorder.recordAigc(
                    context,
                    taskId,
                    "CROSS_DOMAIN_MODEL_UNAVAILABLE",
                    Map.of("assetId", asset.id().toString(), "capabilityCode", capability.code()),
                    Map.of("message", "通用模型交叉复核当前不可用，保留领域模型结果"));
            return Map.of(
                    "status", "UNAVAILABLE",
                    "reason", "通用模型交叉复核执行失败",
                    "detail", exception.getMessage() == null
                            ? exception.getClass().getSimpleName() : exception.getMessage());
        }
    }

    private Map<String, Object> runDiffusionVerificationIfUseful(
            AgentExecutionContext context,
            UUID taskId,
            MediaAsset asset,
            byte[] content,
            String mediaType,
            Map<String, Object> detection) {
        String classification = String.valueOf(detection.getOrDefault("classification", "INCONCLUSIVE"));
        double probability = detection.get("syntheticProbability") instanceof Number number
                ? number.doubleValue() : 0.5;
        boolean useful = "LIKELY_SYNTHETIC".equals(classification)
                || "INCONCLUSIVE".equals(classification)
                || (probability >= 0.35 && probability <= 0.65);
        if (!useful) {
            eventRecorder.recordAigc(
                    context,
                    taskId,
                    "SECONDARY_CHECK_DECIDED",
                    Map.of("assetId", asset.id().toString()),
                    Map.of(
                            "message", "主检测信号明确，本次不追加扩散重建复核",
                            "action", "SKIP",
                            "reason", "主检测结果未触发扩散模型复核条件"));
            return Map.of("status", "SKIPPED", "reason", "主检测结果未触发扩散模型复核条件");
        }
        eventRecorder.recordAigc(
                context,
                taskId,
                "SECONDARY_CHECK_DECIDED",
                Map.of("assetId", asset.id().toString()),
                Map.of(
                        "message", "主检测结果需要独立复核，准备计算扩散重建距离",
                        "action", "RUN",
                        "reason", "检测结果为疑似生成或处于不确定区间"));
        try {
            ForensicModelRegistry.ModelRoute route = modelRegistry.route(
                    asset.contentType(), mediaType, "DIFFUSION_RECONSTRUCTION_VERIFICATION");
            ForensicModelAdapter adapter = modelRegistry.requireAdapter(route.selected().code());
            eventRecorder.recordAigc(
                    context,
                    taskId,
                    "SECONDARY_MODEL_STARTED",
                    Map.of("assetId", asset.id().toString(), "capabilityCode", route.selected().code()),
                    Map.of("message", "正在运行扩散重建痕迹复核", "capabilityName", route.selected().displayName()));
            Map<String, Object> verification = new LinkedHashMap<>(adapter.analyze(content, asset.contentType()));
            verification.put("modelRouting", route.toMap());
            eventRecorder.recordAigc(
                    context,
                    taskId,
                    "SECONDARY_MODEL_COMPLETED",
                    Map.of("assetId", asset.id().toString(), "capabilityCode", route.selected().code()),
                    Map.of(
                            "message", "扩散重建复核已完成",
                            "reconstructionDistance", verification.getOrDefault("reconstructionDistance", "UNAVAILABLE"),
                            "classification", verification.getOrDefault("classification", "INCONCLUSIVE"),
                            "calibrated", Boolean.TRUE.equals(verification.get("calibrated"))));
            return Map.copyOf(verification);
        } catch (RuntimeException exception) {
            eventRecorder.recordAigc(
                    context,
                    taskId,
                    "SECONDARY_MODEL_UNAVAILABLE",
                    Map.of("assetId", asset.id().toString()),
                    Map.of("message", "扩散重建复核当前不可用，保留主检测结果继续分析"));
            return Map.of(
                    "status", "UNAVAILABLE",
                    "reason", "扩散重建复核能力未启用或当前不可用",
                    "detail", exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage());
        }
    }

    private Map<String, Object> compactDetectionEvent(
            ForensicModelRegistry.ModelRoute route, Map<String, Object> detection) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("message", "“" + route.selected().displayName() + "”已完成分析");
        event.put("capabilityName", route.selected().displayName());
        event.put("classification", detection.getOrDefault("classification", "INCONCLUSIVE"));
        event.put("syntheticProbability", detection.getOrDefault("syntheticProbability", "UNAVAILABLE"));
        event.put("qualityStatus", objectMap(detection.get("qualityAssessment")).getOrDefault("status", "UNAVAILABLE"));
        event.put("localizationAvailable", detection.containsKey("localizationOverlayPngBase64")
                || detection.containsKey("attentionOverlayPngBase64"));
        return Map.copyOf(event);
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
