package com.originguard.agentevaluation.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record AgentEvaluationRun(
        UUID id,
        UUID tenantId,
        UUID evaluationCaseId,
        String evaluationCaseName,
        UUID agentTaskId,
        String agentTaskStatus,
        double totalScore,
        boolean passed,
        boolean criticalFailure,
        Map<String, Double> dimensionScores,
        List<Map<String, Object>> violations,
        Map<String, Object> metrics,
        UUID createdBy,
        Instant createdAt) {
    public AgentEvaluationRun {
        dimensionScores = Map.copyOf(dimensionScores);
        violations = violations.stream().map(Map::copyOf).toList();
        metrics = Map.copyOf(metrics);
    }
}
