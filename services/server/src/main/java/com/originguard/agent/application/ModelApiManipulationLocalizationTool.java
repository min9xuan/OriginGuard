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
        name = "originguard.agent.manipulation-localizer.provider",
        havingValue = "model-api",
        matchIfMissing = true)
public class ModelApiManipulationLocalizationTool implements AgentTool {
    private final MediaAssetService mediaAssetService;
    private final AgentArtifactStorage artifactStorage;
    private final ForensicModelRegistry modelRegistry;
    private final ForensicResultCache resultCache;
    private final AgentExecutionEventRecorder eventRecorder;

    public ModelApiManipulationLocalizationTool(
            MediaAssetService mediaAssetService,
            AgentArtifactStorage artifactStorage,
            ForensicModelRegistry modelRegistry,
            ForensicResultCache resultCache,
            AgentExecutionEventRecorder eventRecorder) {
        this.mediaAssetService = mediaAssetService;
        this.artifactStorage = artifactStorage;
        this.modelRegistry = modelRegistry;
        this.resultCache = resultCache;
        this.eventRecorder = eventRecorder;
    }

    @Override
    public String code() {
        return ManipulationLocalizationTool.CODE;
    }

    @Override
    public Map<String, Object> execute(AgentExecutionContext context, Map<String, Object> input) {
        UUID taskId = UUID.fromString(String.valueOf(input.get("agentTaskId")));
        eventRecorder.recordManipulation(context, taskId, "MANIPULATION_MODEL_STARTED", Map.of(), Map.of(
                "message", "正在为关联图片运行 Mesorch 像素级局部篡改定位",
                "assetCount", context.assets().stream().filter(asset -> asset.contentType().startsWith("image/")).count()));
        List<Map<String, Object>> findings = new ArrayList<>();
        for (MediaAsset asset : context.assets()) {
            if (!asset.contentType().startsWith("image/")) continue;
            Map<String, Object> finding = new LinkedHashMap<>();
            finding.put("assetId", asset.id().toString());
            finding.put("filename", asset.originalFilename());
            try {
                ForensicModelRegistry.ModelRoute route = modelRegistry.route(
                        asset.contentType(), mediaType(context, asset.id()), "MANIPULATION_LOCALIZATION");
                ForensicModelAdapter adapter = modelRegistry.requireAdapter(route.selected().code());
                String cacheIdentity = route.selected().code() + ":" + asset.sha256();
                var cached = resultCache.get("manipulation-localization", cacheIdentity);
                Map<String, Object> result = cached.orElseGet(() -> {
                    MediaAssetService.StoredMedia stored = mediaAssetService.readStored(
                            context.actor().tenantId(), asset.id());
                    Map<String, Object> computed = adapter.analyze(stored.content(), asset.contentType());
                    resultCache.put("manipulation-localization", cacheIdentity, computed);
                    return computed;
                });
                result.forEach((key, value) -> {
                    if (!key.endsWith("PngBase64")) finding.put(key, value);
                });
                finding.put("cacheHit", cached.isPresent());
                finding.put("modelRouting", route.toMap());
                storeArtifact(context, taskId, asset, result, finding,
                        "maskPngBase64", "maskArtifact", "MANIPULATION_LOCALIZATION_MASK");
                storeArtifact(context, taskId, asset, result, finding,
                        "heatmapPngBase64", "heatmapArtifact", "MANIPULATION_LOCALIZATION_HEATMAP");
                storeArtifact(context, taskId, asset, result, finding,
                        "overlayPngBase64", "overlayArtifact", "MANIPULATION_LOCALIZATION_OVERLAY");
            } catch (RuntimeException exception) {
                finding.put("provider", "MESORCH");
                finding.put("status", "UNAVAILABLE");
                finding.put("classification", "INCONCLUSIVE");
                finding.put("calibrated", false);
                finding.put("limitations", List.of(
                        "Mesorch 当前未配置或执行失败，本次没有形成篡改定位结论。",
                        "AIGC 检测、C2PA 与其他观察仍可独立用于人工核验。"));
                finding.put("unavailableReason", safeMessage(exception));
            }
            findings.add(Map.copyOf(finding));
        }
        if (findings.isEmpty()) {
            throw new IllegalStateException("Manipulation localization requires at least one linked image asset");
        }
        long succeeded = findings.stream().filter(item -> "SUCCEEDED".equals(item.get("status"))).count();
        eventRecorder.recordManipulation(context, taskId,
                succeeded > 0 ? "MANIPULATION_MODEL_COMPLETED" : "MANIPULATION_MODEL_UNAVAILABLE",
                Map.of(), Map.of(
                        "message", succeeded > 0
                                ? "Mesorch 已输出篡改掩码、热力图与候选区域"
                                : "Mesorch 当前不可用；任务保留其他证据并继续",
                        "analyzedImageCount", findings.size(),
                        "succeededImageCount", succeeded));
        return Map.of(
                "provider", "MESORCH",
                "toolVersion", "1.0.0",
                "analyzedImageCount", findings.size(),
                "succeededImageCount", succeeded,
                "findings", List.copyOf(findings));
    }

    private void storeArtifact(
            AgentExecutionContext context,
            UUID taskId,
            MediaAsset asset,
            Map<String, Object> result,
            Map<String, Object> finding,
            String sourceKey,
            String targetKey,
            String kind) {
        String encoded = String.valueOf(result.getOrDefault(sourceKey, ""));
        if (encoded.isBlank()) return;
        AgentArtifactStorage.StoredArtifact artifact = artifactStorage.storeVisualization(
                context.actor().tenantId(), taskId, asset.id(), Base64.getDecoder().decode(encoded), kind);
        finding.put(targetKey, Map.of(
                "artifactId", artifact.artifactId().toString(),
                "kind", artifact.kind(),
                "contentType", artifact.contentType(),
                "byteSize", artifact.byteSize(),
                "sha256", artifact.sha256()));
    }

    private String mediaType(AgentExecutionContext context, UUID assetId) {
        return String.valueOf(context.mediaTypeContexts().getOrDefault(assetId, Map.of())
                .getOrDefault("mediaType", "UNKNOWN"));
    }

    private String safeMessage(RuntimeException exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }
}
