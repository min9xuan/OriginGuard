package com.originguard.agent.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AgentTask(
        UUID id,
        UUID tenantId,
        UUID caseId,
        UUID createdBy,
        AgentTaskStatus status,
        String goal,
        String selectedSkillCode,
        String selectedSkillVersion,
        int remainingStepBudget,
        Map<String, Object> conclusion,
        String failureCode,
        String failureMessage,
        long checkpointVersion,
        long version,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt,
        Instant updatedAt) {
    public AgentTask {
        conclusion = conclusion == null ? Map.of() : Map.copyOf(conclusion);
    }
}
