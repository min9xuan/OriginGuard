package com.originguard.agent.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AgentStep(
        UUID id,
        UUID taskId,
        int sequenceNumber,
        String stepType,
        String status,
        String skillCode,
        String toolCode,
        Map<String, Object> input,
        Map<String, Object> output,
        Instant createdAt) {
    public AgentStep {
        input = Map.copyOf(input);
        output = Map.copyOf(output);
    }
}
