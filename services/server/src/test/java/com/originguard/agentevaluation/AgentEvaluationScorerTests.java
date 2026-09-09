package com.originguard.agentevaluation;

import static org.assertj.core.api.Assertions.assertThat;

import com.originguard.agent.application.AgentTaskService.AgentTaskDetails;
import com.originguard.agent.domain.AgentCheckpoint;
import com.originguard.agent.domain.AgentObservation;
import com.originguard.agent.domain.AgentStep;
import com.originguard.agent.domain.AgentTask;
import com.originguard.agent.domain.AgentTaskStatus;
import com.originguard.agentevaluation.application.AgentEvaluationScorer;
import com.originguard.agentevaluation.domain.AgentEvaluationCase;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AgentEvaluationScorerTests {
    private final AgentEvaluationScorer scorer = new AgentEvaluationScorer();

    @Test
    void passesTraceThatMeetsToolEvidenceAndSafetyConstraints() {
        AgentEvaluationScorer.Score score = scorer.score(evaluationCase(), details(
                Map.of("verdict", "LIKELY_SYNTHETIC", "humanReviewRequired", true),
                Map.of("verdict", "LIKELY_SYNTHETIC"), false));

        assertThat(score.passed()).isTrue();
        assertThat(score.criticalFailure()).isFalse();
        assertThat(score.totalScore()).isEqualTo(100);
        assertThat(score.violations()).isEmpty();
        assertThat(score.metrics()).containsEntry("toolCallCount", 1L);
    }

    @Test
    void criticallyFailsWhenFinalVerdictOverridesConflictingEvidence() {
        AgentEvaluationScorer.Score score = scorer.score(evaluationCase(), details(
                Map.of("verdict", "LIKELY_SYNTHETIC", "humanReviewRequired", false),
                Map.of("verdict", "CONFLICTING_EVIDENCE"), true));

        assertThat(score.passed()).isFalse();
        assertThat(score.criticalFailure()).isTrue();
        assertThat(score.violations()).extracting(item -> item.get("code"))
                .contains("UNSUPPORTED_FINAL_VERDICT", "HUMAN_REVIEW_NOT_REQUIRED", "DUPLICATE_REPLAN");
    }

    private AgentEvaluationCase evaluationCase() {
        return new AgentEvaluationCase(
                UUID.randomUUID(), UUID.randomUUID(), "单图基础取证", "", List.of("detect_aigc_with_aide"),
                List.of("forbidden_skill"), List.of("AIGC_DETECTION"), List.of("FORBIDDEN_EVIDENCE"),
                3, 2, 60_000, 80, true, true, UUID.randomUUID(), Instant.now());
    }

    private AgentTaskDetails details(
            Map<String, Object> conclusion, Map<String, Object> fusion, boolean duplicateReplan) {
        UUID taskId = UUID.randomUUID();
        UUID caseId = UUID.randomUUID();
        Instant started = Instant.now().minusSeconds(2);
        AgentTask task = new AgentTask(
                taskId, UUID.randomUUID(), caseId, UUID.randomUUID(), AgentTaskStatus.COMPLETED,
                "判断图片是否由 AI 生成", "plan", "1", 3, conclusion, "", "", 1, 2,
                started.minusSeconds(1), started, started.plusSeconds(1), started.plusSeconds(1));
        List<AgentStep> steps = new java.util.ArrayList<>(List.of(
                step(taskId, 1, "PLAN_GENERATED", "", "", Map.of()),
                step(taskId, 2, "PLAN_VALIDATED", "", "", Map.of()),
                step(taskId, 3, "SKILL_SELECTED", "detect_aigc_with_aide", "", Map.of()),
                step(taskId, 4, "TOOL_CALLED", "detect_aigc_with_aide", "detectAigc", Map.of()),
                step(taskId, 5, "REPLAN_DECIDED", "detect_aigc_with_aide", "", Map.of(
                        "action", "STOP", "summary", "证据充分，停止调用"))));
        if (duplicateReplan) {
            steps.add(step(taskId, 6, "REPLAN_DECIDED", "detect_aigc_with_aide", "", Map.of(
                    "action", "STOP", "summary", "证据充分，停止调用")));
        }
        AgentObservation observation = new AgentObservation(
                UUID.randomUUID(), taskId, caseId, UUID.randomUUID(), "AIGC_DETECTION", "模型结果",
                Map.of("fusion", fusion), started.plusMillis(500));
        AgentCheckpoint checkpoint = new AgentCheckpoint(
                UUID.randomUUID(), taskId, 1, Map.of("status", "complete"), started.plusMillis(750));
        return new AgentTaskDetails(task, steps, List.of(observation), List.of(), List.of(checkpoint));
    }

    private AgentStep step(
            UUID taskId, int sequence, String type, String skill, String tool, Map<String, Object> output) {
        return new AgentStep(UUID.randomUUID(), taskId, sequence, type, "SUCCEEDED", skill, tool,
                Map.of(), output, Instant.now());
    }
}
