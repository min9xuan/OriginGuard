package com.originguard.detection.domain;

import java.time.Instant;
import java.util.UUID;

public record EvaluationSample(
        UUID id,
        UUID tenantId,
        UUID assetId,
        String assetFilename,
        String contentType,
        GroundTruth groundTruth,
        MediaCategory mediaCategory,
        String generatorName,
        UUID createdBy,
        Instant createdAt) {

    public enum GroundTruth { AUTHENTIC, SYNTHETIC }

    public enum MediaCategory { PHOTOGRAPH, CARTOON, ILLUSTRATION, OTHER }
}
