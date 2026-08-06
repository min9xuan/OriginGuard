package com.originguard.agent.application;

import com.originguard.investigation.domain.CaseStatus;
import com.originguard.shared.application.ResourceNotFoundException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class SkillRegistry {
    public static final String METADATA_SKILL = "inspect_media_metadata";
    public static final String METADATA_SKILL_VERSION = "1.0.0";

    private final Map<String, SkillDefinition> skills;

    public SkillRegistry() {
        List<SkillDefinition> definitions = List.of(new SkillDefinition(
                METADATA_SKILL,
                METADATA_SKILL_VERSION,
                "Inspect registered media metadata and produce a structured observation",
                Set.of("agent:run", "asset:read", "case:read"),
                Set.of(CaseStatus.INVESTIGATING),
                Set.of(MockMetadataTool.CODE),
                3));
        skills = definitions.stream().collect(Collectors.toUnmodifiableMap(
                SkillDefinition::code, Function.identity()));
    }

    public SkillDefinition require(String code, String version) {
        SkillDefinition skill = skills.get(code);
        if (skill == null || !skill.version().equals(version)) {
            throw new ResourceNotFoundException("SKILL_NOT_FOUND", "Requested skill version was not found");
        }
        return skill;
    }

    public List<SkillDefinition> list() {
        return List.copyOf(skills.values());
    }
}
