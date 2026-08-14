package com.originguard.agent.application;

import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class FakePlanner {
    public static final String PLAN_CODE = "deterministic_media_rag_pipeline";
    public static final String PLAN_VERSION = "1.1.0";

    public List<SkillSelection> plan(AgentExecutionContext context, String goal) {
        return List.of(
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
                        SkillRegistry.RAG_SKILL,
                        SkillRegistry.SKILL_VERSION,
                        "Retrieve published tenant knowledge with traceable document and chunk citations"));
    }

    public record SkillSelection(String skillCode, String skillVersion, String reason) {}
}
