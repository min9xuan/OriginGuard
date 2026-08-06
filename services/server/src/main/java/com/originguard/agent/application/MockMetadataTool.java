package com.originguard.agent.application;

import com.originguard.media.domain.MediaAsset;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class MockMetadataTool implements AgentTool {
    public static final String CODE = "mock.inspect_media_metadata";

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public Map<String, Object> execute(AgentExecutionContext context, Map<String, Object> input) {
        long totalBytes = context.assets().stream().mapToLong(MediaAsset::byteSize).sum();
        List<String> contentTypes = context.assets().stream()
                .map(MediaAsset::contentType)
                .distinct()
                .sorted()
                .toList();
        return Map.of(
                "provider", "MOCK",
                "assetCount", context.assets().size(),
                "totalBytes", totalBytes,
                "contentTypes", contentTypes,
                "humanEvidenceCount", context.humanEvidenceCount(),
                "fileContentInspected", false,
                "warning", "M2.1 inspects registered metadata only; file bytes are not available yet");
    }
}
