package com.originguard.agent.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AgentCheckpoint(
        UUID id,
        UUID taskId,
        long checkpointVersion,
        Map<String, Object> state,
        Instant createdAt) {
    public AgentCheckpoint {
        state = Map.copyOf(state);
    }
}
