package com.originguard.audit.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AuditEntry(
        UUID id,
        UUID tenantId,
        UUID actorUserId,
        String action,
        String resourceType,
        UUID resourceId,
        Map<String, Object> details,
        Instant createdAt) {}
