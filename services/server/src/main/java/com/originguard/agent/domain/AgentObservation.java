package com.originguard.agent.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AgentObservation(
        UUID id,
        UUID taskId,
        UUID caseId,
        UUID assetId,
        String evidenceType,
        String summary,
        Map<String, Object> payload,
        Instant createdAt) {
    public AgentObservation {
        payload = Map.copyOf(payload);
    }
}
