package com.originguard.media.domain;

import java.time.Instant;
import java.util.UUID;

public record MediaAsset(
        UUID id,
        UUID tenantId,
        String originalFilename,
        String contentType,
        long byteSize,
        String sha256,
        String storageStatus,
        UUID createdBy,
        Instant createdAt) {}
