package com.originguard.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.originguard.agent.application.ForensicModelAdapter;
import com.originguard.agent.application.ForensicModelCapability;
import com.originguard.agent.application.ForensicModelRegistry;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ForensicModelRegistryTests {
    @Test
    void routesDigitalIllustrationToGenericModelAndDisclosesCrossDomainGap() {
        ForensicModelRegistry registry = new ForensicModelRegistry(List.of(genericAdapter()));

        ForensicModelRegistry.ModelRoute route = registry.route(
                "image/png", "DIGITAL_ILLUSTRATION", "AIGC_DETECTION");

        assertThat(route.selected().code()).isEqualTo("generic-test");
        assertThat(route.unavailableRecommended())
                .extracting(ForensicModelCapability::code)
                .containsExactly("cross_domain_illustration_aigc_detection");
        assertThat(route.toMap()).containsEntry("degraded", true);
    }

    @Test
    void usesGenericModelDirectlyWhenNoMoreSpecificCapabilityIsRegistered() {
        ForensicModelRegistry registry = new ForensicModelRegistry(List.of(genericAdapter()));

        ForensicModelRegistry.ModelRoute route = registry.route(
                "image/jpeg", "PHOTOGRAPH", "AIGC_DETECTION");

        assertThat(route.selected().code()).isEqualTo("generic-test");
        assertThat(route.unavailableRecommended()).isEmpty();
        assertThat(route.toMap()).containsEntry("degraded", false);
    }

    @Test
    void newlyRegisteredAnimeAdapterOverridesGenericFallback() {
        ForensicModelRegistry registry = new ForensicModelRegistry(List.of(
                genericAdapter(), adapter("anime_aigc_detection", Set.of("ANIME_MANGA"), 100)));

        ForensicModelRegistry.ModelRoute route = registry.route(
                "image/webp", "ANIME_MANGA", "AIGC_DETECTION");

        assertThat(route.selected().code()).isEqualTo("anime_aigc_detection");
        assertThat(route.availableCandidates()).extracting(ForensicModelCapability::code)
                .containsExactly("anime_aigc_detection", "generic-test");
        assertThat(route.unavailableRecommended()).isEmpty();
        assertThat(route.toMap()).containsEntry("degraded", false);
    }

    @Test
    void legacyCombinedIllustrationTypeIsNotAssumedToBeAnime() {
        ForensicModelRegistry registry = new ForensicModelRegistry(List.of(
                genericAdapter(), adapter("anime_aigc_detection", Set.of("ANIME_MANGA"), 100)));

        ForensicModelRegistry.ModelRoute route = registry.route(
                "image/png", "ILLUSTRATION_CARTOON", "AIGC_DETECTION");

        assertThat(route.mediaType()).isEqualTo("DIGITAL_ILLUSTRATION");
        assertThat(route.selected().code()).isEqualTo("generic-test");
    }

    private ForensicModelAdapter genericAdapter() {
        return adapter("generic-test", Set.of("*"), 10);
    }

    private ForensicModelAdapter adapter(String code, Set<String> mediaTypes, int priority) {
        return new ForensicModelAdapter() {
            @Override
            public ForensicModelCapability capability() {
                return new ForensicModelCapability(
                        code, "测试模型", "test", "AIGC_DETECTION",
                        Set.of("IMAGE"), mediaTypes, Set.of("PROBABILITY"), priority,
                        "test", List.of());
            }

            @Override
            public Map<String, Object> analyze(byte[] content, String contentType) {
                return Map.of();
            }
        };
    }
}
