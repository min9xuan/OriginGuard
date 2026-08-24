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
    void routesCartoonToAvailableGenericModelAndDisclosesSpecializedGap() {
        ForensicModelRegistry registry = new ForensicModelRegistry(List.of(genericAdapter()));

        ForensicModelRegistry.ModelRoute route = registry.route(
                "image/png", "ILLUSTRATION_CARTOON", "AIGC_DETECTION");

        assertThat(route.selected().code()).isEqualTo("generic-test");
        assertThat(route.unavailableRecommended())
                .extracting(ForensicModelCapability::code)
                .containsExactly("illustration_aigc_detection");
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
    void newlyRegisteredSpecializedAdapterOverridesGenericFallback() {
        ForensicModelRegistry registry = new ForensicModelRegistry(List.of(
                genericAdapter(), adapter("illustration_aigc_detection", Set.of("ILLUSTRATION_CARTOON"), 100)));

        ForensicModelRegistry.ModelRoute route = registry.route(
                "image/webp", "ILLUSTRATION_CARTOON", "AIGC_DETECTION");

        assertThat(route.selected().code()).isEqualTo("illustration_aigc_detection");
        assertThat(route.unavailableRecommended()).isEmpty();
        assertThat(route.toMap()).containsEntry("degraded", false);
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
