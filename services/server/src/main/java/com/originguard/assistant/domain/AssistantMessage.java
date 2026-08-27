package com.originguard.assistant.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AssistantMessage(
        UUID id,
        UUID tenantId,
        UUID conversationId,
        String role,
        String messageType,
        String content,
        UUID assetId,
        UUID agentTaskId,
        Map<String, Object> grounding,
        Instant createdAt) {
    public AssistantMessage {
        grounding = grounding == null ? Map.of() : Map.copyOf(grounding);
    }
}
