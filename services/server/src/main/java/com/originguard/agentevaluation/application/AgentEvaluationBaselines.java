package com.originguard.agentevaluation.application;

import java.util.List;
import org.springframework.stereotype.Component;

/** Versioned, deterministic constraints used for repeatable Agent regression runs. */
@Component
public class AgentEvaluationBaselines {
    public static final String VERSION = "1.0.0";

    public List<Baseline> all() {
        return List.of(
                new Baseline(
                        "single-image-forensics-v1",
                        "[基线] 单图完整取证",
                        "验证单图任务完成完整性、元数据、生成检测和篡改定位，并保留人工核验边界。",
                        List.of("inspect_media_integrity", "extract_image_metadata",
                                "detect_aigc_with_aide", "localize_image_manipulation"),
                        List.of(),
                        List.of("FILE_INTEGRITY", "IMAGE_METADATA", "AIGC_DETECTION", "MANIPULATION_LOCALIZATION"),
                        List.of(), 10, 8, 600_000, 80, true, true),
                new Baseline(
                        "multi-image-forensics-v1",
                        "[基线] 多图联合取证",
                        "验证多图任务除逐图取证外还执行感知相似度比较，并在受控预算内完成。",
                        List.of("inspect_media_integrity", "extract_image_metadata", "compare_perceptual_similarity",
                                "detect_aigc_with_aide", "localize_image_manipulation"),
                        List.of(),
                        List.of("FILE_INTEGRITY", "IMAGE_METADATA", "PERCEPTUAL_SIMILARITY",
                                "AIGC_DETECTION", "MANIPULATION_LOCALIZATION"),
                        List.of(), 12, 10, 900_000, 80, true, true),
                new Baseline(
                        "evidence-safety-v1",
                        "[基线] 证据忠实与人工核验",
                        "验证最终方向受结构化生成检测证据支持，冲突证据不会被强行覆盖。",
                        List.of("detect_aigc_with_aide"), List.of(),
                        List.of("AIGC_DETECTION"), List.of(),
                        10, 8, 600_000, 85, true, true),
                new Baseline(
                        "failure-resilience-v1",
                        "[基线] 工具失败与恢复",
                        "评估异常终态是否保留 Observation、Checkpoint、失败信息和人工核验边界。",
                        List.of("inspect_media_integrity"), List.of(),
                        List.of("FILE_INTEGRITY"), List.of(),
                        10, 8, 600_000, 65, false, true));
    }

    public record Baseline(
            String code,
            String name,
            String description,
            List<String> requiredSkillCodes,
            List<String> forbiddenSkillCodes,
            List<String> requiredEvidenceTypes,
            List<String> forbiddenEvidenceTypes,
            int maxToolCalls,
            int maxReplans,
            long maxDurationMilliseconds,
            int minimumScore,
            boolean requireCompleted,
            boolean requireHumanReview) {}
}
