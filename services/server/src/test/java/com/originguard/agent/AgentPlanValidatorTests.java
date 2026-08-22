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
    private final SkillRegistry registry = new SkillRegistry();
    private final AgentPlanValidator validator = new AgentPlanValidator(registry);

    @Test
    void loadsSkillPolicyAndInstructionsFromDeclarativeFiles() {
        assertThat(registry.require(SkillRegistry.MEDIA_TYPE_SKILL, SkillRegistry.SKILL_VERSION).prePlanning()).isTrue();
        assertThat(registry.require(SkillRegistry.AIGC_DETECTION_SKILL, SkillRegistry.SKILL_VERSION).required()).isTrue();
        assertThat(registry.require(SkillRegistry.AIGC_DETECTION_SKILL, SkillRegistry.SKILL_VERSION).instructions())
                .isNotBlank()
                .contains("AIDE");
    }

    @Test
    void acceptsWhitelistedPlanWithMandatorySkillsAndSufficientBudget() {
        AgentPlanner.PlannerPlan plan = plan(List.of(
                skill(SkillRegistry.INTEGRITY_SKILL),
                skill(SkillRegistry.AIGC_DETECTION_SKILL),
                skill(SkillRegistry.RAG_SKILL)));

        assertThat(validator.validate(plan, 7)).isSameAs(plan);
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

    @Test
    void rejectsStopBeforeMandatorySkillsHaveCompleted() {
        AgentPlanner.ReplanDecision decision = new AgentPlanner.ReplanDecision(
                AgentPlanner.ReplanAction.STOP, "提前停止", List.of(), Map.of());

        assertThatThrownBy(() -> validator.validateDecision(
                decision,
                List.of(skill(SkillRegistry.AIGC_DETECTION_SKILL), skill(SkillRegistry.RAG_SKILL)),
                List.of(SkillRegistry.INTEGRITY_SKILL),
                5))
                .isInstanceOf(BusinessConflictException.class)
                .hasMessageContaining("cannot stop");
    }

    @Test
    void acceptsReplanThatDropsOptionalSkillButRetainsUnfinishedMandatorySkills() {
        List<AgentPlanner.SkillSelection> remaining = List.of(
                skill(SkillRegistry.METADATA_SKILL),
                skill(SkillRegistry.AIGC_DETECTION_SKILL),
                skill(SkillRegistry.RAG_SKILL));
        AgentPlanner.ReplanDecision decision = new AgentPlanner.ReplanDecision(
                AgentPlanner.ReplanAction.REPLAN,
                "观察已足够，删除可选元数据步骤",
                List.of(skill(SkillRegistry.AIGC_DETECTION_SKILL), skill(SkillRegistry.RAG_SKILL)),
                Map.of());

        assertThat(validator.validateDecision(
                decision, remaining, List.of(SkillRegistry.INTEGRITY_SKILL), 5)).isSameAs(decision);
    }

    private AgentPlanner.PlannerPlan plan(List<AgentPlanner.SkillSelection> skills) {
        return new AgentPlanner.PlannerPlan(
                "test-plan", "1.0.0", "TEST", "test summary", skills, Map.of());
    }

    private AgentPlanner.SkillSelection skill(String code) {
        return new AgentPlanner.SkillSelection(code, SkillRegistry.SKILL_VERSION, "test reason");
    }
}
