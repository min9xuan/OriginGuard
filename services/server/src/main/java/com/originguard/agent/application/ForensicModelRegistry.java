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
        String normalizedType = mediaType == null || mediaType.isBlank() ? "UNKNOWN" : mediaType;
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

    private List<ForensicModelCapability> plannedCapabilities() {
        return List.of(
                new ForensicModelCapability(
                        "illustration_aigc_detection",
                        "插画与卡通生成内容鉴别",
                        "planned",
                        "AIGC_DETECTION",
                        Set.of("IMAGE"),
                        Set.of("ILLUSTRATION_CARTOON"),
                        Set.of("PROBABILITY", "VERDICT", "ATTENTION_MAP"),
                        100,
                        "面向插画、动漫和卡通内容的专用鉴别能力。",
                        List.of("尚未接入可执行模型")),
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
                        List.of("尚未接入可执行模型")),
                new ForensicModelCapability(
                        "content_provenance_verification",
                        "内容来源凭证验证",
                        "planned",
                        "PROVENANCE_VERIFICATION",
                        Set.of("IMAGE", "VIDEO"),
                        Set.of(ForensicModelCapability.ANY_MEDIA_TYPE),
                        Set.of("CREDENTIAL_STATUS", "SIGNER", "EDIT_HISTORY"),
                        80,
                        "验证内容凭证、签名与可追溯编辑历史。",
                        List.of("尚未接入 C2PA 验证器")));
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
            result.put("routingVersion", "1.0.0");
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
