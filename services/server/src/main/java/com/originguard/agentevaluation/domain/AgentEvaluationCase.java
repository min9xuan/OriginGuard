package com.originguard.agentevaluation.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AgentEvaluationCase(
        UUID id,
        UUID tenantId,
        String name,
        String description,
        List<String> requiredSkillCodes,
        List<String> forbiddenSkillCodes,
        List<String> requiredEvidenceTypes,
        List<String> forbiddenEvidenceTypes,
        int maxToolCalls,
        int maxReplans,
        long maxDurationMilliseconds,
        int minimumScore,
        boolean requireCompleted,
        boolean requireHumanReview,
        UUID createdBy,
        Instant createdAt) {
    public AgentEvaluationCase {
        requiredSkillCodes = List.copyOf(requiredSkillCodes);
        forbiddenSkillCodes = List.copyOf(forbiddenSkillCodes);
        requiredEvidenceTypes = List.copyOf(requiredEvidenceTypes);
        forbiddenEvidenceTypes = List.copyOf(forbiddenEvidenceTypes);
    }
}
