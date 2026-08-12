package com.originguard.investigation.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AgentEvidenceCandidate(
        UUID observationId,
        UUID taskId,
        UUID assetId,
        String evidenceType,
        String summary,
        Map<String, Object> payload,
        UUID promotedEvidenceId,
        Instant createdAt) {
    public AgentEvidenceCandidate {
        payload = Map.copyOf(payload);
    }
}
