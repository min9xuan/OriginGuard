package com.originguard.agentevaluation.application;

import com.originguard.agent.application.AgentTaskService.AgentTaskDetails;
import com.originguard.agent.domain.AgentObservation;
import com.originguard.agent.domain.AgentStep;
import com.originguard.agentevaluation.domain.AgentEvaluationCase;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class AgentEvaluationScorer {
    public Score score(AgentEvaluationCase evaluationCase, AgentTaskDetails details) {
        Set<String> skills = values(details.steps(), AgentStep::skillCode);
        Set<String> tools = values(details.steps(), AgentStep::toolCode);
        Set<String> evidenceTypes = details.observations().stream()
                .map(AgentObservation::evidenceType).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        long toolCalls = count(details.steps(), "TOOL_CALLED");
        long replans = details.steps().stream().filter(step ->
                "REPLAN_DECIDED".equals(step.stepType()) || "REPLAN_FALLBACK".equals(step.stepType())).count();
        long duplicateReplans = duplicateReplans(details.steps());
        long duration = durationMillis(details);
        List<Map<String, Object>> violations = new ArrayList<>();

        double toolSelection = coverage(evaluationCase.requiredSkillCodes(), skills) * 12;
        if (intersects(evaluationCase.forbiddenSkillCodes(), skills)) {
            violation(violations, "FORBIDDEN_SKILL_USED", "CRITICAL", "调用了评测规则禁止的 Skill");
        } else toolSelection += 4;
        if (toolCalls <= evaluationCase.maxToolCalls()) toolSelection += 4;
        else violation(violations, "TOOL_BUDGET_EXCEEDED", "ERROR", "工具调用次数超过上限");

        double planning = hasStep(details.steps(), "PLAN_GENERATED") ? 5 : 0;
        planning += hasStep(details.steps(), "PLAN_VALIDATED") ? 5 : 0;
        if (replans <= evaluationCase.maxReplans()) planning += 5;
        else violation(violations, "REPLAN_BUDGET_EXCEEDED", "ERROR", "动态重规划次数超过上限");
        if (duplicateReplans == 0) planning += 5;
        else violation(violations, "DUPLICATE_REPLAN", "WARNING", "存在重复的动态决策");

        double goalCoverage = coverage(evaluationCase.requiredEvidenceTypes(), evidenceTypes) * 10;
        if (goalCoverage < 10) violation(violations, "REQUIRED_EVIDENCE_MISSING", "ERROR", "缺少要求的 Observation 类型");
        if (intersects(evaluationCase.forbiddenEvidenceTypes(), evidenceTypes)) {
            goalCoverage = 0;
            violation(violations, "FORBIDDEN_EVIDENCE_CREATED", "CRITICAL", "产生了评测规则禁止的 Observation 类型");
        }

        boolean humanReview = Boolean.TRUE.equals(details.task().conclusion().get("humanReviewRequired"));
        boolean verdictFaithful = verdictFaithful(details);
        double fidelity = details.observations().isEmpty() ? 0 : 10;
        if (details.observations().isEmpty()) {
            violation(violations, "NO_OBSERVATIONS", "CRITICAL", "任务没有保存任何可追溯 Observation");
        }
        if (!evaluationCase.requireHumanReview() || humanReview) fidelity += 5;
        else violation(violations, "HUMAN_REVIEW_NOT_REQUIRED", "CRITICAL", "结论没有保留人工核验要求");
        if (verdictFaithful) fidelity += 10;
        else violation(violations, "UNSUPPORTED_FINAL_VERDICT", "CRITICAL", "最终结论覆盖或背离了已记录的模型证据");

        boolean completed = "COMPLETED".equals(details.task().status().name());
        double resilience = (!evaluationCase.requireCompleted() || completed) ? 8 : 0;
        if (evaluationCase.requireCompleted() && !completed) {
            violation(violations, "TASK_NOT_COMPLETED", "ERROR", "任务没有完成评测规则要求的终态");
        }
        if (details.task().failureCode() == null || details.task().failureCode().isBlank()) resilience += 4;
        if (!details.checkpoints().isEmpty()) resilience += 3;

        double performance = duration <= evaluationCase.maxDurationMilliseconds() ? 6 : 0;
        if (duration > evaluationCase.maxDurationMilliseconds()) {
            violation(violations, "DURATION_EXCEEDED", "WARNING", "任务总耗时超过评测上限");
        }
        if (toolCalls <= evaluationCase.maxToolCalls()) performance += 4;

        Map<String, Double> dimensions = new LinkedHashMap<>();
        dimensions.put("TOOL_SELECTION", round(toolSelection));
        dimensions.put("PLANNING_REPLAN", round(planning));
        dimensions.put("GOAL_COVERAGE", round(goalCoverage));
        dimensions.put("EVIDENCE_FIDELITY", round(fidelity));
        dimensions.put("RESILIENCE", round(resilience));
        dimensions.put("PERFORMANCE", round(performance));
        double total = round(dimensions.values().stream().mapToDouble(Double::doubleValue).sum());
        boolean critical = violations.stream().anyMatch(item -> "CRITICAL".equals(item.get("severity")));
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("toolCallCount", toolCalls);
        metrics.put("replanCount", replans);
        metrics.put("duplicateReplanCount", duplicateReplans);
        metrics.put("durationMilliseconds", duration);
        metrics.put("recordedSkillCodes", List.copyOf(skills));
        metrics.put("recordedToolCodes", List.copyOf(tools));
        metrics.put("recordedEvidenceTypes", List.copyOf(evidenceTypes));
        metrics.put("stepCount", details.steps().size());
        metrics.put("observationCount", details.observations().size());
        return new Score(total, !critical && total >= evaluationCase.minimumScore(), critical,
                Map.copyOf(dimensions), List.copyOf(violations), Map.copyOf(metrics));
    }

    private boolean verdictFaithful(AgentTaskDetails details) {
        String finalVerdict = String.valueOf(details.task().conclusion().getOrDefault("verdict", "INCONCLUSIVE"));
        List<String> observationVerdicts = details.observations().stream()
                .filter(item -> "AIGC_DETECTION".equals(item.evidenceType()))
                .map(item -> objectMap(item.payload().get("fusion")))
                .map(item -> String.valueOf(item.getOrDefault("verdict", "INCONCLUSIVE")))
                .toList();
        if (observationVerdicts.contains("CONFLICTING_EVIDENCE")) {
            return "CONFLICTING_EVIDENCE".equals(finalVerdict) || "INCONCLUSIVE".equals(finalVerdict);
        }
        if ("LIKELY_SYNTHETIC".equals(finalVerdict) || "LIKELY_AUTHENTIC".equals(finalVerdict)) {
            return observationVerdicts.contains(finalVerdict);
        }
        return true;
    }

    private long durationMillis(AgentTaskDetails details) {
        var task = details.task();
        var start = task.startedAt() == null ? task.createdAt() : task.startedAt();
        var end = task.completedAt() == null ? task.updatedAt() : task.completedAt();
        return Math.max(0, Duration.between(start, end).toMillis());
    }

    private long duplicateReplans(List<AgentStep> steps) {
        Set<String> decisions = new LinkedHashSet<>();
        long duplicates = 0;
        for (AgentStep step : steps) {
            if (!("REPLAN_DECIDED".equals(step.stepType()) || "REPLAN_FALLBACK".equals(step.stepType()))) continue;
            String fingerprint = String.valueOf(step.output().getOrDefault("action", "")) + "|"
                    + String.valueOf(step.output().getOrDefault("summary", ""));
            if (!decisions.add(fingerprint)) duplicates++;
        }
        return duplicates;
    }

    private <T> Set<String> values(List<T> source, java.util.function.Function<T, String> extractor) {
        return source.stream().map(extractor).filter(value -> value != null && !value.isBlank())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private long count(List<AgentStep> steps, String type) {
        return steps.stream().filter(step -> type.equals(step.stepType())).count();
    }

    private boolean hasStep(List<AgentStep> steps, String type) {
        return steps.stream().anyMatch(step -> type.equals(step.stepType()));
    }

    private double coverage(List<String> expected, Set<String> actual) {
        if (expected.isEmpty()) return 1;
        return (double) expected.stream().filter(actual::contains).count() / expected.size();
    }

    private boolean intersects(List<String> expected, Set<String> actual) {
        return expected.stream().anyMatch(actual::contains);
    }

    private void violation(List<Map<String, Object>> result, String code, String severity, String message) {
        result.add(Map.of("code", code, "severity", severity, "message", message));
    }

    private Map<String, Object> objectMap(Object value) {
        if (!(value instanceof Map<?, ?> raw)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, item) -> result.put(String.valueOf(key), item));
        return Map.copyOf(result);
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    public record Score(
            double totalScore,
            boolean passed,
            boolean criticalFailure,
            Map<String, Double> dimensionScores,
            List<Map<String, Object>> violations,
            Map<String, Object> metrics) {}
}
