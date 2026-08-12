package com.originguard.investigation.domain;

import java.time.Instant;
import java.util.UUID;

public record InvestigationEvidence(
        UUID id,
        UUID tenantId,
        UUID caseId,
        UUID assetId,
        String evidenceType,
        String title,
        String observation,
        EvidenceConclusion conclusion,
        EvidenceConfidence confidence,
        UUID sourceObservationId,
        UUID createdBy,
        Instant createdAt) {}
