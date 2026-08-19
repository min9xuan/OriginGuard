package com.originguard.agent.application;

import com.originguard.media.domain.MediaAsset;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "originguard.agent.media-type-classifier.provider", havingValue = "fake")
public class FakeClipMediaTypeTool implements AgentTool {
    @Override
    public String code() {
        return ClipMediaTypeTool.CODE;
    }

    @Override
    public Map<String, Object> execute(AgentExecutionContext context, Map<String, Object> input) {
        List<Map<String, Object>> findings = context.assets().stream()
                .filter(asset -> asset.contentType().startsWith("image/"))
                .map(this::finding)
                .toList();
        return Map.of(
                "provider", "OPENAI_CLIP_TEST_DOUBLE",
                "toolVersion", "1.0.0",
                "analyzedImageCount", findings.size(),
                "findings", findings);
    }

    private Map<String, Object> finding(MediaAsset asset) {
        return Map.ofEntries(
                Map.entry("assetId", asset.id().toString()),
                Map.entry("filename", asset.originalFilename()),
                Map.entry("provider", "OPENAI_CLIP_TEST_DOUBLE"),
                Map.entry("role", "MEDIA_TYPE_CONTEXT"),
                Map.entry("status", "AVAILABLE"),
                Map.entry("model", "ViT-B/32 test double"),
                Map.entry("modelVersion", "test"),
                Map.entry("promptVersion", "3.0.0"),
                Map.entry("mediaType", "PHOTOGRAPH"),
                Map.entry("mediaTypeLabel", "摄影图像"),
                Map.entry("mediaTypeScore", 0.9),
                Map.entry("mediaTypeMargin", 0.7),
                Map.entry("processingMilliseconds", 1),
                Map.entry("limitations", List.of("集成测试替身只提供固定媒体类型")));
    }
}
