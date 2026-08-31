package com.originguard.agent.application;

import com.originguard.shared.application.ResourceNotFoundException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class ForensicModelRegistry {
    private final Map<String, ForensicModelAdapter> adapters;
    private final List<ForensicModelCapability> plannedCapabilities;

    public ForensicModelRegistry(List<ForensicModelAdapter> adapters) {
        this.adapters = adapters.stream().collect(Collectors.toUnmodifiableMap(
                adapter -> adapter.capability().code(), Function.identity()));
        this.plannedCapabilities = plannedCapabilities();
    }

    public ModelRoute route(String contentType, String mediaType, String purpose) {
        String mediaKind = contentType != null && contentType.startsWith("video/") ? "VIDEO" : "IMAGE";
        String normalizedType = normalizeMediaType(mediaType);
        List<ForensicModelCapability> available = adapters.values().stream()
                .map(ForensicModelAdapter::capability)
                .filter(capability -> purpose.equals(capability.purpose()))
                .filter(capability -> capability.supports(mediaKind, normalizedType))
                .sorted(Comparator.comparingInt(ForensicModelCapability::priority).reversed())
                .toList();
        if (available.isEmpty()) {
            throw new ResourceNotFoundException(
                    "FORENSIC_MODEL_UNAVAILABLE", "No available forensic model supports this media type");
        }
        ForensicModelCapability selected = available.getFirst();
        List<ForensicModelCapability> unavailableRecommended = plannedCapabilities.stream()
                .filter(capability -> !adapters.containsKey(capability.code()))
                .filter(capability -> purpose.equals(capability.purpose()))
                .filter(capability -> capability.supports(mediaKind, normalizedType))
                .filter(capability -> capability.priority() > selected.priority())
                .sorted(Comparator.comparingInt(ForensicModelCapability::priority).reversed())
                .toList();
        return new ModelRoute(mediaKind, normalizedType, selected, available, unavailableRecommended);
    }

    public ForensicModelAdapter requireAdapter(String capabilityCode) {
        ForensicModelAdapter adapter = adapters.get(capabilityCode);
        if (adapter == null) {
            throw new ResourceNotFoundException("FORENSIC_MODEL_UNAVAILABLE", "Forensic model adapter is unavailable");
        }
        return adapter;
    }

    public List<Map<String, Object>> catalog() {
        List<Map<String, Object>> result = new ArrayList<>();
        adapters.values().stream()
                .map(ForensicModelAdapter::capability)
                .sorted(Comparator.comparing(ForensicModelCapability::code))
                .map(capability -> capability.toMap(true))
                .forEach(result::add);
        plannedCapabilities.stream()
                .filter(capability -> !adapters.containsKey(capability.code()))
                .sorted(Comparator.comparing(ForensicModelCapability::code))
                .map(capability -> capability.toMap(false))
                .forEach(result::add);
        return List.copyOf(result);
    }

    private String normalizeMediaType(String mediaType) {
        if (mediaType == null || mediaType.isBlank()) return "UNKNOWN";
        // Results persisted before media taxonomy v2 cannot safely be assumed to be anime.
        return "ILLUSTRATION_CARTOON".equals(mediaType) ? "DIGITAL_ILLUSTRATION" : mediaType;
    }

    private List<ForensicModelCapability> plannedCapabilities() {
        return List.of(
                new ForensicModelCapability(
                        "anime_aigc_detection",
                        "动漫与漫画生成内容鉴别",
                        "planned",
                        "AIGC_DETECTION",
                        Set.of("IMAGE"),
                        Set.of("ANIME_MANGA"),
                        Set.of("PROBABILITY", "VERDICT", "ATTENTION_MAP"),
                        100,
                        "面向动漫和漫画内容的专用鉴别能力。",
                        List.of("尚未接入可执行模型")),
                new ForensicModelCapability(
                        "cross_domain_illustration_aigc_detection",
                        "跨域数字插画生成内容鉴别",
                        "planned",
                        "AIGC_DETECTION",
                        Set.of("IMAGE"),
                        Set.of("DIGITAL_ILLUSTRATION", "VECTOR_CARTOON"),
                        Set.of("PROBABILITY", "VERDICT", "ATTENTION_MAP"),
                        90,
                        "面向数字绘画、欧美卡通与矢量插画的跨域鉴别能力。",
                        List.of("尚未接入可执行模型")),
                new ForensicModelCapability(
                        "diffusion_reconstruction_verification",
                        "扩散重建痕迹复核",
                        "planned",
                        "DIFFUSION_RECONSTRUCTION_VERIFICATION",
                        Set.of("IMAGE"),
                        Set.of(ForensicModelCapability.ANY_MEDIA_TYPE),
                        Set.of("RECONSTRUCTION_DISTANCE", "AUXILIARY_VERDICT"),
                        50,
                        "对疑似扩散模型生成的图像执行重建距离复核。",
                        List.of("尚未接入可执行复核器")),
                new ForensicModelCapability(
                        "render_aigc_detection",
                        "三维渲染生成内容鉴别",
                        "planned",
                        "AIGC_DETECTION",
                        Set.of("IMAGE"),
                        Set.of("THREE_D_RENDER"),
                        Set.of("PROBABILITY", "VERDICT", "ATTENTION_MAP"),
                        90,
                        "面向三维渲染和游戏画面的专用鉴别能力。",
                        List.of("尚未接入可执行模型")),
                new ForensicModelCapability(
                        "manipulation_localization",
                        "局部篡改定位",
                        "planned",
                        "MANIPULATION_LOCALIZATION",
                        Set.of("IMAGE"),
                        Set.of(ForensicModelCapability.ANY_MEDIA_TYPE),
                        Set.of("TAMPER_PROBABILITY", "LOCALIZATION_MASK"),
                        80,
                        "定位拼接、修补或局部生成区域。",
                        List.of("尚未接入可执行模型")));
    }

    public record ModelRoute(
            String mediaKind,
            String mediaType,
            ForensicModelCapability selected,
            List<ForensicModelCapability> availableCandidates,
            List<ForensicModelCapability> unavailableRecommended) {
        public ModelRoute {
            availableCandidates = List.copyOf(availableCandidates);
            unavailableRecommended = List.copyOf(unavailableRecommended);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("routingVersion", "2.0.0");
            result.put("mediaKind", mediaKind);
            result.put("mediaType", mediaType);
            result.put("selectedCapability", selected.toMap(true));
            result.put("availableCandidates", availableCandidates.stream()
                    .map(capability -> capability.toMap(true)).toList());
            result.put("recommendedUnavailable", unavailableRecommended.stream()
                    .map(capability -> capability.toMap(false)).toList());
            result.put("degraded", !unavailableRecommended.isEmpty());
            result.put("reason", unavailableRecommended.isEmpty()
                    ? "已选择当前媒体类型下优先级最高的可用模型"
                    : "更匹配的专用模型尚未接入，当前降级使用通用鉴别模型");
            return Map.copyOf(result);
        }
    }
}
