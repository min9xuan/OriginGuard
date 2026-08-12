package com.originguard.investigation.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ReviewTask(
        UUID id,
        UUID tenantId,
        UUID caseId,
        UUID reviewerId,
        ReviewStatus status,
        String decisionReason,
        UUID createdBy,
        UUID decidedBy,
        List<UUID> citedEvidenceIds,
        long version,
        Instant createdAt,
        Instant decidedAt) {
    public ReviewTask {
        citedEvidenceIds = List.copyOf(citedEvidenceIds);
    }
}
