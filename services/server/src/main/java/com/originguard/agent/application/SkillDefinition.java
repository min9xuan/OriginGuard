package com.originguard.agent.application;

import com.originguard.investigation.domain.CaseStatus;
import java.util.Set;

public record SkillDefinition(
        String code,
        String version,
        String description,
        Set<String> requiredPermissions,
        Set<CaseStatus> allowedCaseStatuses,
        Set<String> allowedTools,
        int maxSteps) {
    public SkillDefinition {
        requiredPermissions = Set.copyOf(requiredPermissions);
        allowedCaseStatuses = Set.copyOf(allowedCaseStatuses);
        allowedTools = Set.copyOf(allowedTools);
    }
}
