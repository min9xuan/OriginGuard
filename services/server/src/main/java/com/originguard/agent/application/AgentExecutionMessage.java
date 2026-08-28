package com.originguard.agent.application;

import java.util.UUID;

public record AgentExecutionMessage(
        UUID taskId,
        UUID userId,
        long expectedVersion,
        UUID conversationId,
        UUID assetId,
        String question,
        long enqueuedAtEpochMillis) {}
