package com.originguard.investigation.domain;

import java.time.Instant;
import java.util.UUID;

public record InvestigationCase(
        UUID id,
        UUID tenantId,
        String caseNumber,
        String title,
        String description,
        CasePriority priority,
        CaseStatus status,
        UUID createdBy,
        UUID assignedInvestigatorId,
        UUID assignedReviewerId,
        long version,
        Instant createdAt,
        Instant updatedAt) {}
