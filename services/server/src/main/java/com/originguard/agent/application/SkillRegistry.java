package com.originguard.agent.application;

import com.originguard.investigation.domain.CaseStatus;
import com.originguard.shared.application.ResourceNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

@Component
public class SkillRegistry {
    public static final String INTEGRITY_SKILL = "verify_media_integrity";
    public static final String METADATA_SKILL = "extract_image_metadata";
    public static final String SIMILARITY_SKILL = "compare_perceptual_similarity";
    public static final String MEDIA_TYPE_SKILL = "classify_media_type_with_clip";
    public static final String AIGC_DETECTION_SKILL = "detect_aigc_with_aide";
    public static final String RAG_SKILL = "retrieve_forensic_guidance";
    public static final String SKILL_VERSION = "1.0.0";

    private final Map<String, SkillDefinition> skills;

    public SkillRegistry() {
        this.skills = loadSkills();
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

    public List<SkillDefinition> plannable() {
        return skills.values().stream().filter(skill -> !skill.prePlanning()).toList();
    }

    public List<SkillDefinition> requiredPlannable() {
        return skills.values().stream()
                .filter(skill -> skill.required() && !skill.prePlanning())
                .toList();
    }

    private Map<String, SkillDefinition> loadSkills() {
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:agent-skills/*/SKILL.md");
            Arrays.sort(resources, (left, right) -> left.getDescription().compareTo(right.getDescription()));
            Map<String, SkillDefinition> loaded = new LinkedHashMap<>();
            for (Resource resource : resources) {
                SkillDefinition skill = parse(resource);
                if (loaded.putIfAbsent(skill.code(), skill) != null) {
                    throw new IllegalStateException("Duplicate Agent Skill code: " + skill.code());
                }
            }
            if (loaded.isEmpty()) {
                throw new IllegalStateException("No declarative Agent Skills were found");
            }
            return Map.copyOf(loaded);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to load declarative Agent Skills", exception);
        }
    }

    private SkillDefinition parse(Resource resource) throws IOException {
        String content = resource.getContentAsString(StandardCharsets.UTF_8);
        if (!content.startsWith("---")) {
            throw new IllegalStateException(resource.getDescription() + " must start with YAML front matter");
        }
        int end = content.indexOf("\n---", 3);
        if (end < 0) {
            throw new IllegalStateException(resource.getDescription() + " has invalid YAML front matter");
        }
        Map<String, String> metadata = new LinkedHashMap<>();
        for (String line : content.substring(3, end).lines().toList()) {
            if (line.isBlank()) continue;
            int separator = line.indexOf(':');
            if (separator < 1) {
                throw new IllegalStateException(resource.getDescription() + " has invalid metadata: " + line);
            }
            metadata.put(line.substring(0, separator).trim(), line.substring(separator + 1).trim());
        }
        return new SkillDefinition(
                required(metadata, "code", resource),
                required(metadata, "version", resource),
                required(metadata, "description", resource),
                content.substring(end + 4).trim(),
                csv(metadata, "requiredPermissions"),
                csv(metadata, "allowedCaseStatuses").stream().map(CaseStatus::valueOf).collect(java.util.stream.Collectors.toSet()),
                csv(metadata, "allowedTools"),
                Integer.parseInt(required(metadata, "maxSteps", resource)),
                Boolean.parseBoolean(required(metadata, "required", resource)),
                Boolean.parseBoolean(required(metadata, "prePlanning", resource)));
    }

    private Set<String> csv(Map<String, String> metadata, String key) {
        return Arrays.stream(metadata.getOrDefault(key, "").split(","))
                .map(String::trim).filter(value -> !value.isBlank()).collect(java.util.stream.Collectors.toSet());
    }

    private String required(Map<String, String> metadata, String key, Resource resource) {
        String value = metadata.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(resource.getDescription() + " is missing " + key);
        }
        return value;
    }
}
