package com.originguard.agent.application;

import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "originguard.agent.planner.provider",
        havingValue = "fake",
        matchIfMissing = true)
public class FakePlanner implements AgentPlanner {
    public static final String PLAN_CODE = "deterministic_media_rag_pipeline";
    public static final String PLAN_VERSION = "1.3.0";

    public PlannerPlan plan(AgentExecutionContext context, String goal) {
        List<SkillSelection> skills = List.of(
                new SkillSelection(
                        SkillRegistry.INTEGRITY_SKILL,
                        SkillRegistry.SKILL_VERSION,
                        "Verify stored bytes before deriving any other evidence"),
                new SkillSelection(
                        SkillRegistry.METADATA_SKILL,
                        SkillRegistry.SKILL_VERSION,
                        "Extract deterministic image structure and metadata facts"),
                new SkillSelection(
                        SkillRegistry.SIMILARITY_SKILL,
                        SkillRegistry.SKILL_VERSION,
                        "Compare perceptual hashes across all media linked to the case"),
                new SkillSelection(
                        SkillRegistry.AIGC_DETECTION_SKILL,
                        SkillRegistry.SKILL_VERSION,
                        "Run AIDE after CLIP media typing and interpret the score within that media domain"),
                new SkillSelection(
                        SkillRegistry.RAG_SKILL,
                        SkillRegistry.SKILL_VERSION,
                        "Retrieve published tenant knowledge with traceable document and chunk citations"));
        return new PlannerPlan(
                PLAN_CODE,
                PLAN_VERSION,
                "FAKE",
                "CLIP media typing is ready; run the fixed deterministic evidence pipeline with type-aware AIDE interpretation",
                skills,
                Map.of("mode", "DETERMINISTIC", "assetCount", context.assets().size()));
    }
}
