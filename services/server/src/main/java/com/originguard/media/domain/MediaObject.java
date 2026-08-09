package com.originguard.media.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record MediaObject(
        UUID assetId,
        UUID tenantId,
        String objectKey,
        String detectedContentType,
        int pixelWidth,
        int pixelHeight,
        String perceptualHash,
        Map<String, Object> extractedMetadata,
        Instant storedAt) {}
