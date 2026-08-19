package com.originguard.agent.application;

import com.originguard.shared.application.BusinessConflictException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class AgentPlanValidator {
    private static final Set<String> REQUIRED_SKILLS =
            Set.of(
                    SkillRegistry.INTEGRITY_SKILL,
                    SkillRegistry.AIGC_DETECTION_SKILL,
                    SkillRegistry.RAG_SKILL);
    private final SkillRegistry skillRegistry;

    public AgentPlanValidator(SkillRegistry skillRegistry) {
        this.skillRegistry = skillRegistry;
    }

    public AgentPlanner.PlannerPlan validate(AgentPlanner.PlannerPlan plan, int stepBudget) {
        int plannableSkillCount = skillRegistry.list().size() - 1;
        if (plan.skills().isEmpty() || plan.skills().size() > plannableSkillCount) {
            throw invalid("Planner must select between 1 and " + plannableSkillCount + " skills");
        }
        Set<String> selected = new HashSet<>();
        for (AgentPlanner.SkillSelection selection : plan.skills()) {
            if (selection.reason() == null || selection.reason().isBlank()) {
                throw invalid("Every selected skill must include a reason");
            }
            skillRegistry.require(selection.skillCode(), selection.skillVersion());
            if (SkillRegistry.MEDIA_TYPE_SKILL.equals(selection.skillCode())) {
                throw invalid("CLIP media typing is a pre-planning Harness step and cannot be selected twice");
            }
            if (!selected.add(selection.skillCode())) {
                throw invalid("Planner selected a duplicate skill: " + selection.skillCode());
            }
        }
        if (!selected.containsAll(REQUIRED_SKILLS)) {
            throw invalid("Planner omitted a policy-required skill: " + requiredMissing(selected));
        }
        int requiredBudget = plan.skills().size() * 2 + 1;
        if (stepBudget < requiredBudget) {
            throw invalid("Plan requires " + requiredBudget + " steps but task budget is " + stepBudget);
        }
        return plan;
    }

    private List<String> requiredMissing(Set<String> selected) {
        return REQUIRED_SKILLS.stream().filter(skill -> !selected.contains(skill)).sorted().toList();
    }

    private BusinessConflictException invalid(String message) {
        return new BusinessConflictException("AGENT_PLAN_INVALID", message);
    }
}
