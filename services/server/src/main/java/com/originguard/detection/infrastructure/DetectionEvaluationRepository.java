package com.originguard.detection.infrastructure;

import com.originguard.detection.domain.EvaluationRun;
import com.originguard.detection.domain.EvaluationSample;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Repository
public class DetectionEvaluationRepository {
    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public DetectionEvaluationRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    public EvaluationSample insertSample(
            UUID id,
            UUID tenantId,
            UUID assetId,
            EvaluationSample.GroundTruth groundTruth,
            EvaluationSample.MediaCategory mediaCategory,
            String generatorName,
            UUID createdBy) {
        jdbcClient.sql("""
                        INSERT INTO detection_evaluation_sample(
                            id, tenant_id, asset_id, ground_truth, media_category, generator_name, created_by
                        ) VALUES (:id, :tenantId, :assetId, :groundTruth, :mediaCategory, :generatorName, :createdBy)
                        """)
                .param("id", id).param("tenantId", tenantId).param("assetId", assetId)
                .param("groundTruth", groundTruth.name()).param("mediaCategory", mediaCategory.name())
                .param("generatorName", generatorName).param("createdBy", createdBy).update();
        return findSamples(tenantId).stream().filter(sample -> sample.id().equals(id)).findFirst().orElseThrow();
    }

    public List<EvaluationSample> findSamples(UUID tenantId) {
        return jdbcClient.sql("""
                        SELECT s.id, s.tenant_id, s.asset_id, a.original_filename, a.content_type,
                               s.ground_truth, s.media_category, s.generator_name,
                               s.created_by, s.created_at
                        FROM detection_evaluation_sample s
                        JOIN media_asset a ON a.id = s.asset_id AND a.tenant_id = s.tenant_id
                        WHERE s.tenant_id = :tenantId
                        ORDER BY s.created_at DESC, s.id
                        """)
                .param("tenantId", tenantId).query(this::mapSample).list();
    }

    public boolean deleteSample(UUID tenantId, UUID sampleId) {
        return jdbcClient.sql("DELETE FROM detection_evaluation_sample WHERE tenant_id=:tenantId AND id=:id")
                .param("tenantId", tenantId).param("id", sampleId).update() == 1;
    }

    public void insertRun(
            UUID id,
            UUID tenantId,
            String modelCode,
            String modelVersion,
            double evaluationThreshold,
            double recommendedThreshold,
            EvaluationRun.Metrics metrics,
            Map<String, EvaluationRun.CategoryMetrics> categoryMetrics,
            UUID createdBy) {
        jdbcClient.sql("""
                        INSERT INTO detection_evaluation_run(
                            id, tenant_id, model_code, model_version, evaluation_threshold,
                            recommended_threshold, sample_count, true_positive, true_negative,
                            false_positive, false_negative, accuracy, precision_score, recall_score,
                            f1_score, category_metrics, created_by
                        ) VALUES (
                            :id, :tenantId, :modelCode, :modelVersion, :evaluationThreshold,
                            :recommendedThreshold, :sampleCount, :tp, :tn, :fp, :fn,
                            :accuracy, :precision, :recall, :f1, CAST(:categoryMetrics AS jsonb), :createdBy
                        )
                        """)
                .param("id", id).param("tenantId", tenantId).param("modelCode", modelCode)
                .param("modelVersion", modelVersion).param("evaluationThreshold", evaluationThreshold)
                .param("recommendedThreshold", recommendedThreshold).param("sampleCount", metrics.sampleCount())
                .param("tp", metrics.truePositive()).param("tn", metrics.trueNegative())
                .param("fp", metrics.falsePositive()).param("fn", metrics.falseNegative())
                .param("accuracy", metrics.accuracy()).param("precision", metrics.precision())
                .param("recall", metrics.recall()).param("f1", metrics.f1())
                .param("categoryMetrics", writeJson(categoryMetrics)).param("createdBy", createdBy).update();
    }

    public void insertResult(
            UUID id,
            UUID tenantId,
            UUID runId,
            EvaluationSample sample,
            double probability,
            EvaluationSample.GroundTruth predicted,
            long processingMilliseconds,
            String qualityStatus) {
        jdbcClient.sql("""
                        INSERT INTO detection_evaluation_result(
                            id, tenant_id, run_id, sample_id, asset_id, asset_filename, ground_truth,
                            synthetic_probability, predicted_label, correct,
                            processing_milliseconds, quality_status
                        ) VALUES (
                            :id, :tenantId, :runId, :sampleId, :assetId, :assetFilename, :groundTruth,
                            :probability, :predicted, :correct, :processingMilliseconds, :qualityStatus
                        )
                        """)
                .param("id", id).param("tenantId", tenantId).param("runId", runId)
                .param("sampleId", sample.id()).param("assetId", sample.assetId())
                .param("assetFilename", sample.assetFilename())
                .param("groundTruth", sample.groundTruth().name()).param("probability", probability)
                .param("predicted", predicted.name()).param("correct", predicted == sample.groundTruth())
                .param("processingMilliseconds", processingMilliseconds).param("qualityStatus", qualityStatus)
                .update();
    }

    public List<EvaluationRun> findRuns(UUID tenantId) {
        return jdbcClient.sql("""
                        SELECT * FROM detection_evaluation_run
                        WHERE tenant_id=:tenantId ORDER BY created_at DESC, id
                        """)
                .param("tenantId", tenantId).query((rs, row) -> mapRun(tenantId, rs)).list();
    }

    private List<EvaluationRun.Result> findResults(UUID tenantId, UUID runId) {
        return jdbcClient.sql("""
                        SELECT r.id, r.sample_id, r.asset_id, r.asset_filename, r.ground_truth,
                               r.synthetic_probability, r.predicted_label, r.correct,
                               r.processing_milliseconds, r.quality_status
                        FROM detection_evaluation_result r
                        WHERE r.tenant_id=:tenantId AND r.run_id=:runId
                        ORDER BY r.synthetic_probability DESC, r.id
                        """)
                .param("tenantId", tenantId).param("runId", runId)
                .query((rs, row) -> new EvaluationRun.Result(
                        rs.getObject("id", UUID.class), rs.getObject("sample_id", UUID.class),
                        rs.getObject("asset_id", UUID.class), rs.getString("asset_filename"),
                        EvaluationSample.GroundTruth.valueOf(rs.getString("ground_truth")),
                        rs.getDouble("synthetic_probability"),
                        EvaluationSample.GroundTruth.valueOf(rs.getString("predicted_label")),
                        rs.getBoolean("correct"), rs.getLong("processing_milliseconds"),
                        rs.getString("quality_status"))).list();
    }

    private EvaluationSample mapSample(ResultSet rs, int row) throws SQLException {
        return new EvaluationSample(
                rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getObject("asset_id", UUID.class), rs.getString("original_filename"),
                rs.getString("content_type"),
                EvaluationSample.GroundTruth.valueOf(rs.getString("ground_truth")),
                EvaluationSample.MediaCategory.valueOf(rs.getString("media_category")),
                rs.getString("generator_name"), rs.getObject("created_by", UUID.class),
                rs.getTimestamp("created_at").toInstant());
    }

    private EvaluationRun mapRun(UUID tenantId, ResultSet rs) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        EvaluationRun.Metrics metrics = new EvaluationRun.Metrics(
                rs.getInt("sample_count"), rs.getInt("true_positive"), rs.getInt("true_negative"),
                rs.getInt("false_positive"), rs.getInt("false_negative"), rs.getDouble("accuracy"),
                rs.getDouble("precision_score"), rs.getDouble("recall_score"), rs.getDouble("f1_score"));
        return new EvaluationRun(
                id, tenantId, rs.getString("model_code"), rs.getString("model_version"),
                rs.getDouble("evaluation_threshold"), rs.getDouble("recommended_threshold"), metrics,
                readCategoryMetrics(rs.getString("category_metrics")), findResults(tenantId, id),
                rs.getObject("created_by", UUID.class), rs.getTimestamp("created_at").toInstant());
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("Unable to serialize evaluation metrics", exception);
        }
    }

    private Map<String, EvaluationRun.CategoryMetrics> readCategoryMetrics(String json) throws SQLException {
        try {
            Map<String, Map<String, Object>> raw = objectMapper.readValue(json, new TypeReference<>() {});
            Map<String, EvaluationRun.CategoryMetrics> result = new LinkedHashMap<>();
            raw.forEach((category, value) -> {
                Map<?, ?> metrics = value.get("metrics") instanceof Map<?, ?> item ? item : Map.of();
                result.put(category, new EvaluationRun.CategoryMetrics(
                        number(value.get("recommendedThreshold")), new EvaluationRun.Metrics(
                                integer(metrics.get("sampleCount")), integer(metrics.get("truePositive")),
                                integer(metrics.get("trueNegative")), integer(metrics.get("falsePositive")),
                                integer(metrics.get("falseNegative")), number(metrics.get("accuracy")),
                                number(metrics.get("precision")), number(metrics.get("recall")),
                                number(metrics.get("f1")))));
            });
            return Map.copyOf(result);
        } catch (JacksonException exception) {
            throw new SQLException("Invalid category metrics JSON", exception);
        }
    }

    private int integer(Object value) { return value instanceof Number number ? number.intValue() : 0; }
    private double number(Object value) { return value instanceof Number number ? number.doubleValue() : 0; }
}
