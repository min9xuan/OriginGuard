package com.originguard.agent.application;

import org.springframework.stereotype.Component;

@Component
public class FakePlanner {
    public SkillSelection select(AgentExecutionContext context, String goal) {
        return new SkillSelection(
                SkillRegistry.METADATA_SKILL,
                SkillRegistry.METADATA_SKILL_VERSION,
                "M3.1 deterministic planner always starts with controlled basic media forensics");
    }

    public record SkillSelection(String skillCode, String skillVersion, String reason) {}
}
