package com.originguard.detection.application;

import com.originguard.detection.domain.EvaluationRun;
import com.originguard.detection.domain.EvaluationSample;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class DetectionMetricsCalculator {
    private DetectionMetricsCalculator() {}

    public static EvaluationRun.Metrics calculate(List<ScoredLabel> values, double threshold) {
        int tp = 0;
        int tn = 0;
        int fp = 0;
        int fn = 0;
        for (ScoredLabel value : values) {
            boolean predictedSynthetic = value.syntheticProbability() >= threshold;
            boolean actuallySynthetic = value.groundTruth() == EvaluationSample.GroundTruth.SYNTHETIC;
            if (predictedSynthetic && actuallySynthetic) tp++;
            else if (predictedSynthetic) fp++;
            else if (actuallySynthetic) fn++;
            else tn++;
        }
        int count = values.size();
        double accuracy = ratio(tp + tn, count);
        double precision = ratio(tp, tp + fp);
        double recall = ratio(tp, tp + fn);
        double f1 = precision + recall == 0 ? 0 : 2 * precision * recall / (precision + recall);
        return new EvaluationRun.Metrics(count, tp, tn, fp, fn, accuracy, precision, recall, f1);
    }

    public static double recommendThreshold(List<ScoredLabel> values) {
        List<Double> candidates = new ArrayList<>();
        candidates.add(0.0);
        candidates.add(0.5);
        candidates.add(1.0);
        values.stream().map(ScoredLabel::syntheticProbability).distinct().forEach(candidates::add);
        return candidates.stream()
                .distinct()
                .filter(value -> value >= 0 && value <= 1)
                .max(Comparator
                        .comparingDouble((Double threshold) -> calculate(values, threshold).f1())
                        .thenComparingDouble(threshold -> -falsePositiveRate(calculate(values, threshold)))
                        .thenComparingDouble(threshold -> -Math.abs(threshold - 0.5)))
                .orElse(0.5);
    }

    public static boolean hasBothClasses(List<ScoredLabel> values) {
        return values.stream().anyMatch(value -> value.groundTruth() == EvaluationSample.GroundTruth.AUTHENTIC)
                && values.stream().anyMatch(value -> value.groundTruth() == EvaluationSample.GroundTruth.SYNTHETIC);
    }

    private static double falsePositiveRate(EvaluationRun.Metrics metrics) {
        return ratio(metrics.falsePositive(), metrics.falsePositive() + metrics.trueNegative());
    }

    private static double ratio(int numerator, int denominator) {
        return denominator == 0 ? 0 : (double) numerator / denominator;
    }

    public record ScoredLabel(EvaluationSample.GroundTruth groundTruth, double syntheticProbability) {}
}
