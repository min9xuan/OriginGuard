package com.originguard.investigation.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ReviewTask(
        UUID id,
        UUID tenantId,
        UUID caseId,
        UUID reviewerId,
        ReviewStatus status,
        EvidenceConclusion finalConclusion,
        String decisionReason,
        boolean agentAssessmentIncluded,
        UUID agentTaskId,
        Map<String, Object> agentAssessmentSnapshot,
        UUID createdBy,
        UUID decidedBy,
        List<UUID> citedEvidenceIds,
        long version,
        Instant createdAt,
        Instant decidedAt) {
    public ReviewTask {
        citedEvidenceIds = List.copyOf(citedEvidenceIds);
        agentAssessmentSnapshot = Map.copyOf(agentAssessmentSnapshot);
    }
}
