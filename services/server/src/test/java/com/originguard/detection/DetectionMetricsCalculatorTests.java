package com.originguard.detection;

import static org.assertj.core.api.Assertions.assertThat;

import com.originguard.detection.application.DetectionMetricsCalculator;
import com.originguard.detection.application.DetectionMetricsCalculator.ScoredLabel;
import com.originguard.detection.domain.EvaluationSample.GroundTruth;
import java.util.List;
import org.junit.jupiter.api.Test;

class DetectionMetricsCalculatorTests {
    @Test
    void calculatesConfusionMatrixAndClassificationMetrics() {
        var values = List.of(
                new ScoredLabel(GroundTruth.AUTHENTIC, 0.1),
                new ScoredLabel(GroundTruth.AUTHENTIC, 0.7),
                new ScoredLabel(GroundTruth.SYNTHETIC, 0.6),
                new ScoredLabel(GroundTruth.SYNTHETIC, 0.8));

        var metrics = DetectionMetricsCalculator.calculate(values, 0.5);

        assertThat(metrics.sampleCount()).isEqualTo(4);
        assertThat(metrics.truePositive()).isEqualTo(2);
        assertThat(metrics.trueNegative()).isEqualTo(1);
        assertThat(metrics.falsePositive()).isEqualTo(1);
        assertThat(metrics.falseNegative()).isZero();
        assertThat(metrics.accuracy()).isEqualTo(0.75);
        assertThat(metrics.precision()).isCloseTo(2.0 / 3.0, within(1e-9));
        assertThat(metrics.recall()).isEqualTo(1.0);
        assertThat(metrics.f1()).isCloseTo(0.8, within(1e-9));
    }

    @Test
    void recommendsThresholdByF1ThenFalsePositiveRate() {
        var values = List.of(
                new ScoredLabel(GroundTruth.AUTHENTIC, 0.2),
                new ScoredLabel(GroundTruth.AUTHENTIC, 0.7),
                new ScoredLabel(GroundTruth.SYNTHETIC, 0.6),
                new ScoredLabel(GroundTruth.SYNTHETIC, 0.8));

        // 0.5 and 0.6 have the same F1/FPR here; prefer the stable default when tied.
        assertThat(DetectionMetricsCalculator.recommendThreshold(values)).isEqualTo(0.5);
        assertThat(DetectionMetricsCalculator.hasBothClasses(values)).isTrue();
    }

    private static org.assertj.core.data.Offset<Double> within(double value) {
        return org.assertj.core.data.Offset.offset(value);
    }
}
