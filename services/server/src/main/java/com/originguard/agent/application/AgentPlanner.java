package com.originguard.agent.application;

import java.util.List;
import java.util.Map;

public interface AgentPlanner {
    PlannerPlan plan(AgentExecutionContext context, String goal);

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
}
