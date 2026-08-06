package com.originguard.agent.application;

import com.originguard.identity.domain.CurrentActor;
import com.originguard.investigation.domain.InvestigationCase;
import com.originguard.shared.application.BusinessConflictException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

@Component
public class AgentPolicyEngine {
    public void requireCanRun(
            CurrentActor actor, InvestigationCase investigationCase, SkillDefinition skill, String toolCode) {
        if (!actor.userId().equals(investigationCase.assignedInvestigatorId())) {
            throw new AccessDeniedException("Only the assigned investigator can run the agent task");
        }
        for (String permission : skill.requiredPermissions()) {
            if (!actor.hasPermission(permission)) {
                throw new AccessDeniedException("Missing permission: " + permission);
            }
        }
        if (!skill.allowedCaseStatuses().contains(investigationCase.status())) {
            throw new BusinessConflictException(
                    "AGENT_SKILL_STATUS_NOT_ALLOWED", "Skill cannot run in the current case status");
        }
        if (!skill.allowedTools().contains(toolCode)) {
            throw new AccessDeniedException("Tool is outside the selected skill allowlist");
        }
    }
}
