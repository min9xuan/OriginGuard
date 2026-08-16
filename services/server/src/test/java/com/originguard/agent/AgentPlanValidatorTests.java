package com.originguard.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.originguard.agent.application.AgentPlanValidator;
import com.originguard.agent.application.AgentPlanner;
import com.originguard.agent.application.SkillRegistry;
import com.originguard.shared.application.BusinessConflictException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentPlanValidatorTests {
    private final AgentPlanValidator validator = new AgentPlanValidator(new SkillRegistry());

    @Test
    void acceptsWhitelistedPlanWithMandatorySkillsAndSufficientBudget() {
        AgentPlanner.PlannerPlan plan = plan(List.of(
                skill(SkillRegistry.INTEGRITY_SKILL),
                skill(SkillRegistry.RAG_SKILL)));

        assertThat(validator.validate(plan, 5)).isSameAs(plan);
    }

    @Test
    void rejectsPlanThatOmitsPolicyRequiredSkill() {
        AgentPlanner.PlannerPlan plan = plan(List.of(
                skill(SkillRegistry.INTEGRITY_SKILL),
                skill(SkillRegistry.METADATA_SKILL)));

        assertThatThrownBy(() -> validator.validate(plan, 9))
                .isInstanceOf(BusinessConflictException.class)
                .hasMessageContaining("policy-required");
    }

    @Test
    void rejectsDuplicateSkillBeforeHarnessExecution() {
        AgentPlanner.PlannerPlan plan = plan(List.of(
                skill(SkillRegistry.INTEGRITY_SKILL),
                skill(SkillRegistry.INTEGRITY_SKILL),
                skill(SkillRegistry.RAG_SKILL)));

        assertThatThrownBy(() -> validator.validate(plan, 9))
                .isInstanceOf(BusinessConflictException.class)
                .hasMessageContaining("duplicate");
    }

    private AgentPlanner.PlannerPlan plan(List<AgentPlanner.SkillSelection> skills) {
        return new AgentPlanner.PlannerPlan(
                "test-plan", "1.0.0", "TEST", "test summary", skills, Map.of());
    }

    private AgentPlanner.SkillSelection skill(String code) {
        return new AgentPlanner.SkillSelection(code, SkillRegistry.SKILL_VERSION, "test reason");
    }
}
