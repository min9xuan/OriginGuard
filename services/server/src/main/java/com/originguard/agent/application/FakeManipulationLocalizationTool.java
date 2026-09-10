package com.originguard.agent.application;

import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "originguard.agent.manipulation-localizer.provider", havingValue = "fake")
public class FakeManipulationLocalizationTool implements AgentTool {
    @Override
    public String code() {
        return ManipulationLocalizationTool.CODE;
    }

    @Override
    public Map<String, Object> execute(AgentExecutionContext context, Map<String, Object> input) {
        List<Map<String, Object>> findings = context.assets().stream()
                .filter(asset -> asset.contentType().startsWith("image/"))
                .map(asset -> Map.<String, Object>ofEntries(
                        Map.entry("assetId", asset.id().toString()),
                        Map.entry("filename", asset.originalFilename()),
                        Map.entry("provider", "MESORCH_TEST_DOUBLE"),
                        Map.entry("status", "SUCCEEDED"),
                        Map.entry("classification", "INCONCLUSIVE"),
                        Map.entry("tamperedProbability", 0.5),
                        Map.entry("tamperedAreaRatio", 0.0),
                        Map.entry("threshold", 0.5),
                        Map.entry("calibrated", false),
                        Map.entry("regions", List.of()),
                        Map.entry("limitations", List.of("测试替身不执行真实篡改定位"))))
                .toList();
        return Map.of(
                "provider", "MESORCH_TEST_DOUBLE",
                "toolVersion", "1.0.0",
                "analyzedImageCount", findings.size(),
                "succeededImageCount", findings.size(),
                "findings", findings);
    }
}
