package com.originguard.agent.application;

import java.util.List;
import java.util.Map;

public interface AgentPlanner {
    PlannerPlan plan(AgentExecutionContext context, String goal);

    default ReplanDecision replan(ReplanRequest request) {
        return request.remainingSkills().isEmpty()
                ? new ReplanDecision(ReplanAction.STOP, "既定取证步骤已完成，进入结果汇总", List.of(), Map.of())
                : new ReplanDecision(ReplanAction.CONTINUE, "当前观察未要求调整计划，继续执行既定步骤", request.remainingSkills(), Map.of());
    }

    record PlannerPlan(
            String planCode,
            String planVersion,
            String provider,
            String summary,
            List<SkillSelection> skills,
            Map<String, Object> trace) {
        public PlannerPlan {
            skills = List.copyOf(skills);
            trace = Map.copyOf(trace);
        }
    }

    record SkillSelection(String skillCode, String skillVersion, String reason) {}

    enum ReplanAction { CONTINUE, REPLAN, STOP }

    record ObservationDigest(String evidenceType, String summary, Map<String, Object> facts) {
        public ObservationDigest {
            facts = facts == null ? Map.of() : Map.copyOf(facts);
        }
    }

    record ReplanRequest(
            AgentExecutionContext context,
            String goal,
            PlannerPlan initialPlan,
            List<SkillSelection> remainingSkills,
            List<String> completedSkillCodes,
            List<ObservationDigest> latestObservations,
            int remainingStepBudget,
            int decisionNumber) {
        public ReplanRequest {
            remainingSkills = List.copyOf(remainingSkills);
            completedSkillCodes = List.copyOf(completedSkillCodes);
            latestObservations = List.copyOf(latestObservations);
        }
    }

    record ReplanDecision(
            ReplanAction action,
            String summary,
            List<SkillSelection> remainingSkills,
            Map<String, Object> trace) {
        public ReplanDecision {
            remainingSkills = remainingSkills == null ? List.of() : List.copyOf(remainingSkills);
            trace = trace == null ? Map.of() : Map.copyOf(trace);
        }
    }
}
