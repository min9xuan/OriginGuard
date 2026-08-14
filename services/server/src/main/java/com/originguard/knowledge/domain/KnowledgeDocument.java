package com.originguard.knowledge.domain;

import java.time.Instant;
import java.util.UUID;

public record KnowledgeDocument(
        UUID id, UUID tenantId, String title, String documentType, String content, String status,
        int publishedVersion, UUID createdBy, UUID updatedBy, long version,
        Instant createdAt, Instant updatedAt, Instant publishedAt) {}
