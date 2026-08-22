package com.originguard.agent.application;

import com.originguard.investigation.domain.CaseStatus;
import java.util.Set;

public record SkillDefinition(
        String code,
        String version,
        String description,
        String instructions,
        Set<String> requiredPermissions,
        Set<CaseStatus> allowedCaseStatuses,
        Set<String> allowedTools,
        int maxSteps,
        boolean required,
        boolean prePlanning) {
    public SkillDefinition {
        instructions = instructions == null ? "" : instructions.trim();
        requiredPermissions = Set.copyOf(requiredPermissions);
        allowedCaseStatuses = Set.copyOf(allowedCaseStatuses);
        allowedTools = Set.copyOf(allowedTools);
    }
}
