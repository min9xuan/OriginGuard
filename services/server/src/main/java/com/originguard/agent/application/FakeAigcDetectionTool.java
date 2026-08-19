package com.originguard.agent.application;

import com.originguard.media.domain.MediaAsset;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "originguard.agent.aigc-detector.provider", havingValue = "fake")
public class FakeAigcDetectionTool implements AgentTool {
    @Override
    public String code() {
        return AigcDetectionTool.CODE;
    }

    @Override
    public Map<String, Object> execute(AgentExecutionContext context, Map<String, Object> input) {
        List<Map<String, Object>> findings = context.assets().stream()
                .filter(asset -> asset.contentType().startsWith("image/"))
                .map(asset -> finding(asset, context.mediaTypeContexts().getOrDefault(asset.id(), Map.of())))
                .toList();
        return Map.ofEntries(
                Map.entry("provider", "AIDE_TEST_DOUBLE"),
                Map.entry("toolVersion", "1.0.0"),
                Map.entry("model", "AIDE test double"),
                Map.entry("modelVersion", "test"),
                Map.entry("checkpointSha256", "0".repeat(64)),
                Map.entry("device", "cpu"),
                Map.entry("analyzedImageCount", findings.size()),
                Map.entry("overallClassification", "INCONCLUSIVE"),
                Map.entry("overallVerdict", "INCONCLUSIVE"),
                Map.entry("maximumSyntheticProbability", 0.5),
                Map.entry("findings", findings),
                Map.entry("limitations", List.of("集成测试替身不产生真实检测结论")));
    }

    private Map<String, Object> finding(MediaAsset asset, Map<String, Object> mediaTypeContext) {
        return Map.ofEntries(
                Map.entry("assetId", asset.id().toString()),
                Map.entry("filename", asset.originalFilename()),
                Map.entry("provider", "AIDE_TEST_DOUBLE"),
                Map.entry("model", "AIDE test double"),
                Map.entry("modelVersion", "test"),
                Map.entry("checkpointSha256", "0".repeat(64)),
                Map.entry("device", "cpu"),
                Map.entry("precision", "float32"),
                Map.entry("syntheticProbability", 0.5),
                Map.entry("authenticProbability", 0.5),
                Map.entry("classification", "INCONCLUSIVE"),
                Map.entry("syntheticThreshold", 0.8),
                Map.entry("authenticThreshold", 0.2),
                Map.entry("processingMilliseconds", 1),
                Map.entry("qualityAssessment", Map.of(
                        "status", "PASS", "modelEligible", true, "qualityScore", 100, "issues", List.of())),
                Map.entry("mediaTypeContext", mediaTypeContext),
                Map.entry("fusion", Map.of(
                        "policyVersion", AigcEvidenceFusion.POLICY_VERSION,
                        "verdict", "INCONCLUSIVE", "confidence", "LOW",
                        "agreement", "PRIMARY_INCONCLUSIVE", "decisionReady", false,
                        "reasons", List.of("测试替身未形成方向性结论"), "limitations", List.of())),
                Map.entry("limitations", List.of("集成测试替身")));
    }
}
