package com.originguard.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.originguard.agent.application.AigcEvidenceFusion;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AigcEvidenceFusionTests {
    private final AigcEvidenceFusion fusion = new AigcEvidenceFusion();

    @Test
    void refusesDirectionalConclusionWithoutMediaTypeContext() {
        Map<String, Object> result = fusion.fuse(
                primary("LIKELY_SYNTHETIC"),
                Map.of("status", "NOT_CONFIGURED", "classification", "INCONCLUSIVE"),
                quality("PASS"));

        assertThat(result).containsEntry("verdict", "INCONCLUSIVE");
        assertThat(result).containsEntry("agreement", "MEDIA_TYPE_UNAVAILABLE");
        assertThat(result).containsEntry("decisionReady", false);
    }

    @Test
    void requiresDomainCalibrationForCartoon() {
        Map<String, Object> result = fusion.fuse(
                primary("LIKELY_SYNTHETIC"),
                mediaType("ILLUSTRATION_CARTOON", "插画或卡通"),
                quality("PASS"));

        assertThat(result).containsEntry("verdict", "INCONCLUSIVE");
        assertThat(result).containsEntry("agreement", "DOMAIN_CALIBRATION_REQUIRED");
    }

    @Test
    void appliesPhotographContextWithoutClaimingIndependentValidation() {
        Map<String, Object> result = fusion.fuse(
                primary("LIKELY_AUTHENTIC"),
                mediaType("PHOTOGRAPH", "摄影图像"),
                quality("PASS"));

        assertThat(result).containsEntry("verdict", "LIKELY_AUTHENTIC");
        assertThat(result).containsEntry("confidence", "LOW");
        assertThat(result).containsEntry("agreement", "TYPE_CONTEXT_APPLIED");
        assertThat(result).containsEntry("decisionReady", false);
    }

    @Test
    void mediaTypeDoesNotOverrideInconclusiveAide() {
        Map<String, Object> result = fusion.fuse(
                primary("INCONCLUSIVE"),
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
                primary("INCONCLUSIVE"),
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
                primary("UNSUPPORTED_INPUT"),
                Map.of("status", "SKIPPED", "classification", "INCONCLUSIVE"),
                quality("REJECT"));

        assertThat(result).containsEntry("verdict", "UNSUPPORTED_INPUT");
        assertThat(result).containsEntry("confidence", "UNAVAILABLE");
    }

    private Map<String, Object> primary(String classification) {
        return Map.of("classification", classification);
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
