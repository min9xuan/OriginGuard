package com.originguard.detection.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record EvaluationRun(
        UUID id,
        UUID tenantId,
        String modelCode,
        String modelVersion,
        double evaluationThreshold,
        double recommendedThreshold,
        Metrics metrics,
        Map<String, CategoryMetrics> categoryMetrics,
        List<Result> results,
        UUID createdBy,
        Instant createdAt) {

    public record Metrics(
            int sampleCount,
            int truePositive,
            int trueNegative,
            int falsePositive,
            int falseNegative,
            double accuracy,
            double precision,
            double recall,
            double f1) {}

    public record CategoryMetrics(double recommendedThreshold, Metrics metrics) {}

    public record Result(
            UUID id,
            UUID sampleId,
            UUID assetId,
            String assetFilename,
            EvaluationSample.GroundTruth groundTruth,
            double syntheticProbability,
            EvaluationSample.GroundTruth predictedLabel,
            boolean correct,
            long processingMilliseconds,
            String qualityStatus) {}
}
