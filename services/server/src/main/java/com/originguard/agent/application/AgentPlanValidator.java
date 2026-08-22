package com.originguard.agent.application;

import com.originguard.shared.application.BusinessConflictException;
import java.util.HashSet;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class AgentPlanValidator {
    private final SkillRegistry skillRegistry;

    public AgentPlanValidator(SkillRegistry skillRegistry) {
        this.skillRegistry = skillRegistry;
    }

    public AgentPlanner.PlannerPlan validate(AgentPlanner.PlannerPlan plan, int stepBudget) {
        int plannableSkillCount = skillRegistry.plannable().size();
        if (plan.skills().isEmpty() || plan.skills().size() > plannableSkillCount) {
            throw invalid("Planner must select between 1 and " + plannableSkillCount + " skills");
        }
        Set<String> selected = new HashSet<>();
        for (AgentPlanner.SkillSelection selection : plan.skills()) {
            if (selection.reason() == null || selection.reason().isBlank()) {
                throw invalid("Every selected skill must include a reason");
            }
            skillRegistry.require(selection.skillCode(), selection.skillVersion());
            if (skillRegistry.require(selection.skillCode(), selection.skillVersion()).prePlanning()) {
                throw invalid("CLIP media typing is a pre-planning Harness step and cannot be selected twice");
            }
            if (!selected.add(selection.skillCode())) {
                throw invalid("Planner selected a duplicate skill: " + selection.skillCode());
            }
        }
        Set<String> requiredSkills = requiredSkills();
        if (!selected.containsAll(requiredSkills)) {
            throw invalid("Planner omitted a policy-required skill: " + requiredMissing(selected));
        }
        int requiredBudget = plan.skills().size() * 2 + 1;
        if (stepBudget < requiredBudget) {
            throw invalid("Plan requires " + requiredBudget + " steps but task budget is " + stepBudget);
        }
        return plan;
    }

    public AgentPlanner.ReplanDecision validateDecision(
            AgentPlanner.ReplanDecision decision,
            java.util.List<AgentPlanner.SkillSelection> currentRemaining,
            java.util.List<String> completedSkillCodes,
            int remainingStepBudget) {
        if (decision == null || decision.action() == null || decision.summary() == null || decision.summary().isBlank()) {
            throw invalid("Replan decision must include an action and summary");
        }
        Set<String> completed = new HashSet<>(completedSkillCodes);
        if (decision.action() == AgentPlanner.ReplanAction.STOP) {
            if (!completed.containsAll(requiredSkills())) {
                throw invalid("Agent cannot stop before policy-required skills are completed");
            }
            if (!decision.remainingSkills().isEmpty()) {
                throw invalid("STOP decision cannot contain remaining skills");
            }
            return decision;
        }
        if (decision.remainingSkills().isEmpty()) {
            throw invalid("CONTINUE or REPLAN decision must retain at least one skill");
        }
        if (decision.action() == AgentPlanner.ReplanAction.CONTINUE
                && !sameSkillOrder(decision.remainingSkills(), currentRemaining)) {
            throw invalid("CONTINUE decision must preserve the current remaining plan");
        }
        Set<String> selected = new HashSet<>();
        for (AgentPlanner.SkillSelection selection : decision.remainingSkills()) {
            if (selection.reason() == null || selection.reason().isBlank()) {
                throw invalid("Every replanned skill must include a reason");
            }
            SkillDefinition skill = skillRegistry.require(selection.skillCode(), selection.skillVersion());
            if (skill.prePlanning() || completed.contains(skill.code()) || !selected.add(skill.code())) {
                throw invalid("Replan contains a pre-planning, completed or duplicate skill: " + skill.code());
            }
        }
        Set<String> covered = new HashSet<>(completed);
        covered.addAll(selected);
        if (!covered.containsAll(requiredSkills())) {
            throw invalid("Replan omitted an unfinished policy-required skill: " + requiredMissing(covered));
        }
        int requiredBudget = decision.remainingSkills().size() * 2 + 1;
        if (remainingStepBudget < requiredBudget) {
            throw invalid("Replan requires " + requiredBudget + " steps but task budget is " + remainingStepBudget);
        }
        return decision;
    }

    private boolean sameSkillOrder(
            java.util.List<AgentPlanner.SkillSelection> left,
            java.util.List<AgentPlanner.SkillSelection> right) {
        return left.stream().map(AgentPlanner.SkillSelection::skillCode).toList()
                .equals(right.stream().map(AgentPlanner.SkillSelection::skillCode).toList());
    }

    private Set<String> requiredSkills() {
        return skillRegistry.requiredPlannable().stream().map(SkillDefinition::code).collect(java.util.stream.Collectors.toSet());
    }

    private java.util.List<String> requiredMissing(Set<String> selected) {
        return requiredSkills().stream().filter(skill -> !selected.contains(skill)).sorted().toList();
    }

    private BusinessConflictException invalid(String message) {
        return new BusinessConflictException("AGENT_PLAN_INVALID", message);
    }
}
