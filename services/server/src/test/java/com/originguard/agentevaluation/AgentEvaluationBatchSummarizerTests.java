package com.originguard.agentevaluation;

import static org.assertj.core.api.Assertions.assertThat;

import com.originguard.agentevaluation.application.AgentEvaluationBatchSummarizer;
import com.originguard.agentevaluation.application.AgentEvaluationBaselines;
import com.originguard.agentevaluation.domain.AgentEvaluationRun;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AgentEvaluationBatchSummarizerTests {
    private final AgentEvaluationBatchSummarizer summarizer = new AgentEvaluationBatchSummarizer();

    @Test
    void summarizesPassRateP95DimensionsAndViolations() {
        List<AgentEvaluationRun> runs = List.of(
                run(90, true, false, 1_000, Map.of("PERFORMANCE", 10.0), List.of()),
                run(80, true, false, 2_000, Map.of("PERFORMANCE", 8.0),
                        List.of(Map.of("code", "DURATION_EXCEEDED", "severity", "WARNING"))),
                run(40, false, true, 10_000, Map.of("PERFORMANCE", 0.0),
                        List.of(Map.of("code", "NO_OBSERVATIONS", "severity", "CRITICAL"))));

        AgentEvaluationBatchSummarizer.Summary result = summarizer.summarize(runs);

        assertThat(result.totalTasks()).isEqualTo(3);
        assertThat(result.passedTasks()).isEqualTo(2);
        assertThat(result.passRate()).isEqualTo(66.67);
        assertThat(result.averageScore()).isEqualTo(70);
        assertThat(result.p95DurationMilliseconds()).isEqualTo(10_000);
        assertThat(result.criticalFailureCount()).isEqualTo(1);
        assertThat(result.violationCounts()).containsEntry("NO_OBSERVATIONS", 1L);
        assertThat(result.averageDimensionScores()).containsEntry("PERFORMANCE", 6.0);
    }

    @Test
    void exposesVersionedUniqueBaselineScenarios() {
        List<AgentEvaluationBaselines.Baseline> baselines = new AgentEvaluationBaselines().all();

        assertThat(baselines).hasSizeGreaterThanOrEqualTo(4);
        assertThat(baselines).extracting(AgentEvaluationBaselines.Baseline::code).doesNotHaveDuplicates();
        assertThat(baselines).allMatch(item -> item.requireHumanReview() && item.minimumScore() > 0);
    }

    private AgentEvaluationRun run(
            double score, boolean passed, boolean critical, long duration,
            Map<String, Double> dimensions, List<Map<String, Object>> violations) {
        return new AgentEvaluationRun(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "case", UUID.randomUUID(),
                "COMPLETED", score, passed, critical, dimensions, violations,
                Map.of("durationMilliseconds", duration), UUID.randomUUID(), Instant.now());
    }
}
