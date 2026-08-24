package com.originguard.agent.application;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Describes one independently pluggable forensic model capability. */
public record ForensicModelCapability(
        String code,
        String displayName,
        String version,
        String purpose,
        Set<String> mediaKinds,
        Set<String> mediaTypes,
        Set<String> outputTypes,
        int priority,
        String description,
        List<String> limitations) {
    public static final String ANY_MEDIA_TYPE = "*";

    public ForensicModelCapability {
        mediaKinds = Set.copyOf(mediaKinds);
        mediaTypes = Set.copyOf(mediaTypes);
        outputTypes = Set.copyOf(outputTypes);
        limitations = List.copyOf(limitations);
    }

    public boolean supports(String mediaKind, String mediaType) {
        return mediaKinds.contains(mediaKind)
                && (mediaTypes.contains(ANY_MEDIA_TYPE) || mediaTypes.contains(mediaType));
    }

    public Map<String, Object> toMap(boolean available) {
        return Map.ofEntries(
                Map.entry("code", code),
                Map.entry("displayName", displayName),
                Map.entry("version", version),
                Map.entry("purpose", purpose),
                Map.entry("mediaKinds", mediaKinds),
                Map.entry("mediaTypes", mediaTypes),
                Map.entry("outputTypes", outputTypes),
                Map.entry("priority", priority),
                Map.entry("available", available),
                Map.entry("description", description),
                Map.entry("limitations", limitations));
    }
}
