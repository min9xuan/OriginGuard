package com.originguard.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.originguard.agent.application.AigcEvidenceFusion;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AigcEvidenceFusionTests {
    private final AigcEvidenceFusion fusion = new AigcEvidenceFusion();

    @Test
    void keepsAidePreliminaryConclusionWithoutMediaTypeContext() {
        Map<String, Object> result = fusion.fuse(
                primary("LIKELY_SYNTHETIC", 0.91),
                Map.of("status", "NOT_CONFIGURED", "classification", "INCONCLUSIVE"),
                quality("PASS"));

        assertThat(result).containsEntry("verdict", "LIKELY_SYNTHETIC");
        assertThat(result).containsEntry("agreement", "PRELIMINARY_WITHOUT_TYPE_CONTEXT");
        assertThat(result).containsEntry("decisionReady", true);
        assertThat(result).containsEntry("humanReviewRequired", true);
    }

    @Test
    void routesCartoonWithoutDiscardingAidePreliminaryConclusion() {
        Map<String, Object> result = fusion.fuse(
                primary("LIKELY_SYNTHETIC", 0.91),
                mediaType("ILLUSTRATION_CARTOON", "插画或卡通"),
                quality("PASS"));

        assertThat(result).containsEntry("verdict", "LIKELY_SYNTHETIC");
        assertThat(result).containsEntry("agreement", "PRELIMINARY_WITH_TYPE_CONTEXT");
        assertThat(result).containsEntry("decisionReady", true);
        assertThat(result).containsEntry("recommendedDomainDetector", "CARTOON_AIGC_DETECTOR");
        assertThat(result.get("limitations").toString()).contains("专用 AIGC 检测模型");
    }

    @Test
    void appliesPhotographContextWithoutClaimingIndependentValidation() {
        Map<String, Object> result = fusion.fuse(
                primary("LIKELY_AUTHENTIC", 0.09),
                mediaType("PHOTOGRAPH", "摄影图像"),
                quality("PASS"));

        assertThat(result).containsEntry("verdict", "LIKELY_AUTHENTIC");
        assertThat(result).containsEntry("confidence", "HIGH");
        assertThat(result).containsEntry("agreement", "PRELIMINARY_WITH_TYPE_CONTEXT");
        assertThat(result).containsEntry("decisionReady", true);
    }

    @Test
    void mediaTypeDoesNotOverrideInconclusiveAide() {
        Map<String, Object> result = fusion.fuse(
                primary("INCONCLUSIVE", 0.5),
                mediaType("PHOTOGRAPH", "摄影图像"),
                quality("PASS"));

        assertThat(result).containsEntry("verdict", "INCONCLUSIVE");
        assertThat(result).containsEntry("agreement", "PRIMARY_INCONCLUSIVE");
        assertThat(result).containsEntry("confidence", "LOW");
        assertThat(result).containsEntry("decisionReady", false);
    }

    @Test
    void explainsThatDirectionalClipCannotReplaceInconclusiveAide() {
        Map<String, Object> result = fusion.fuse(
                primary("INCONCLUSIVE", 0.5),
                mediaType("ILLUSTRATION_CARTOON", "插画或卡通"),
                quality("PASS"));

        assertThat(result).containsEntry("verdict", "INCONCLUSIVE");
        assertThat(result).containsEntry("agreement", "PRIMARY_INCONCLUSIVE");
        assertThat(result).containsEntry("decisionReady", false);
        assertThat(result.get("reasons").toString()).contains("AIDE").contains("CLIP");
    }

    @Test
    void rejectsUnsupportedImageBeforeModelFusion() {
        Map<String, Object> result = fusion.fuse(
                primary("UNSUPPORTED_INPUT", 0.0),
                Map.of("status", "SKIPPED", "classification", "INCONCLUSIVE"),
                quality("REJECT"));

        assertThat(result).containsEntry("verdict", "UNSUPPORTED_INPUT");
        assertThat(result).containsEntry("confidence", "UNAVAILABLE");
    }

    private Map<String, Object> primary(String classification, double syntheticProbability) {
        return Map.of(
                "classification", classification,
                "syntheticProbability", syntheticProbability,
                "syntheticThreshold", 0.5,
                "authenticThreshold", 0.5);
    }

    private Map<String, Object> quality(String status) {
        return Map.of("status", status);
    }

    private Map<String, Object> mediaType(String code, String label) {
        return Map.of(
                "provider", "OPENAI_CLIP", "role", "MEDIA_TYPE_CONTEXT", "status", "AVAILABLE",
                "mediaType", code, "mediaTypeLabel", label, "mediaTypeScore", 0.9);
    }
}
