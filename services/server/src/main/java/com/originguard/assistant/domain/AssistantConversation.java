package com.originguard.assistant.domain;

import java.time.Instant;
import java.util.UUID;

public record AssistantConversation(
        UUID id,
        UUID tenantId,
        UUID userId,
        String title,
        Instant createdAt,
        Instant updatedAt) {}
