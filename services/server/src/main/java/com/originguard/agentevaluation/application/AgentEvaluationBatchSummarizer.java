package com.originguard.agentevaluation.application;

import com.originguard.agentevaluation.domain.AgentEvaluationRun;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class AgentEvaluationBatchSummarizer {
    public Summary summarize(List<AgentEvaluationRun> runs) {
        if (runs.isEmpty()) {
            return new Summary(List.of(), 0, 0, 0, 0, 0, 0, Map.of(), Map.of());
        }
        int passed = (int) runs.stream().filter(AgentEvaluationRun::passed).count();
        int critical = (int) runs.stream().filter(AgentEvaluationRun::criticalFailure).count();
        double average = round(runs.stream().mapToDouble(AgentEvaluationRun::totalScore).average().orElse(0));
        double passRate = round((double) passed * 100 / runs.size());
        List<Long> durations = runs.stream().map(run -> number(run.metrics().get("durationMilliseconds")))
                .sorted().toList();
        int p95Index = Math.max(0, (int) Math.ceil(durations.size() * 0.95) - 1);

        Map<String, Long> violations = new LinkedHashMap<>();
        runs.stream().flatMap(run -> run.violations().stream()).forEach(item ->
                violations.merge(String.valueOf(item.getOrDefault("code", "UNKNOWN")), 1L, Long::sum));

        Map<String, List<Double>> dimensionValues = new LinkedHashMap<>();
        runs.forEach(run -> run.dimensionScores().forEach((key, value) ->
                dimensionValues.computeIfAbsent(key, ignored -> new ArrayList<>()).add(value)));
        Map<String, Double> dimensions = new LinkedHashMap<>();
        dimensionValues.forEach((key, values) -> dimensions.put(key,
                round(values.stream().mapToDouble(Double::doubleValue).average().orElse(0))));

        return new Summary(
                List.copyOf(runs), runs.size(), passed, passRate, average, durations.get(p95Index), critical,
                Map.copyOf(violations), Map.copyOf(dimensions));
    }

    private long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0;
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    public record Summary(
            List<AgentEvaluationRun> runs,
            int totalTasks,
            int passedTasks,
            double passRate,
            double averageScore,
            long p95DurationMilliseconds,
            int criticalFailureCount,
            Map<String, Long> violationCounts,
            Map<String, Double> averageDimensionScores) {}
}
