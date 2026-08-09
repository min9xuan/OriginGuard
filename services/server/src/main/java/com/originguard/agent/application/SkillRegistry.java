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
    public static final String INTEGRITY_SKILL = "verify_media_integrity";
    public static final String METADATA_SKILL = "extract_image_metadata";
    public static final String SIMILARITY_SKILL = "compare_perceptual_similarity";
    public static final String SKILL_VERSION = "1.0.0";

    private final Map<String, SkillDefinition> skills;

    public SkillRegistry() {
        Set<String> permissions = Set.of("agent:run", "asset:read", "case:read");
        Set<CaseStatus> statuses = Set.of(CaseStatus.INVESTIGATING);
        List<SkillDefinition> definitions = List.of(
                new SkillDefinition(
                        INTEGRITY_SKILL, SKILL_VERSION,
                        "Verify stored bytes against registered size, MIME and SHA-256",
                        permissions, statuses, Set.of(MediaIntegrityTool.CODE), 2),
                new SkillDefinition(
                        METADATA_SKILL, SKILL_VERSION,
                        "Extract deterministic image dimensions, format and EXIF metadata",
                        permissions, statuses, Set.of(ImageMetadataTool.CODE), 2),
                new SkillDefinition(
                        SIMILARITY_SKILL, SKILL_VERSION,
                        "Compare 64-bit difference hashes for media linked to the same case",
                        permissions, statuses, Set.of(PerceptualSimilarityTool.CODE), 2));
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
