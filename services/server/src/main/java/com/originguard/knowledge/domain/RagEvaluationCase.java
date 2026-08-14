package com.originguard.knowledge.domain;

import java.time.Instant;
import java.util.UUID;

public record RagEvaluationCase(
        UUID id,
        UUID tenantId,
        String name,
        String query,
        UUID expectedDocumentId,
        UUID expectedChunkId,
        UUID createdBy,
        Instant createdAt) {}
