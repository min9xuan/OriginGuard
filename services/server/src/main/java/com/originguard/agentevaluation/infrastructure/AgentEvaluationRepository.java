package com.originguard.agentevaluation.infrastructure;

import com.originguard.agentevaluation.domain.AgentEvaluationCase;
import com.originguard.agentevaluation.domain.AgentEvaluationRun;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Repository
public class AgentEvaluationRepository {
    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public AgentEvaluationRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    public AgentEvaluationCase insertCase(
            UUID id, UUID tenantId, String name, String description,
            List<String> requiredSkills, List<String> forbiddenSkills,
            List<String> requiredEvidence, List<String> forbiddenEvidence,
            int maxToolCalls, int maxReplans, long maxDurationMilliseconds,
            int minimumScore, boolean requireCompleted, boolean requireHumanReview, UUID createdBy) {
        jdbcClient.sql("""
                        INSERT INTO agent_evaluation_case(
                            id, tenant_id, name, description, required_skill_codes, forbidden_skill_codes,
                            required_evidence_types, forbidden_evidence_types, max_tool_calls, max_replans,
                            max_duration_milliseconds, minimum_score, require_completed,
                            require_human_review, created_by
                        ) VALUES (
                            :id, :tenantId, :name, :description, CAST(:requiredSkills AS jsonb),
                            CAST(:forbiddenSkills AS jsonb), CAST(:requiredEvidence AS jsonb),
                            CAST(:forbiddenEvidence AS jsonb), :maxToolCalls, :maxReplans,
                            :maxDuration, :minimumScore, :requireCompleted, :requireHumanReview, :createdBy
                        )
                        """)
                .param("id", id).param("tenantId", tenantId).param("name", name)
                .param("description", description).param("requiredSkills", toJson(requiredSkills))
                .param("forbiddenSkills", toJson(forbiddenSkills)).param("requiredEvidence", toJson(requiredEvidence))
                .param("forbiddenEvidence", toJson(forbiddenEvidence)).param("maxToolCalls", maxToolCalls)
                .param("maxReplans", maxReplans).param("maxDuration", maxDurationMilliseconds)
                .param("minimumScore", minimumScore).param("requireCompleted", requireCompleted)
                .param("requireHumanReview", requireHumanReview).param("createdBy", createdBy).update();
        return findCase(tenantId, id).orElseThrow();
    }

    public Optional<AgentEvaluationCase> findCase(UUID tenantId, UUID id) {
        return jdbcClient.sql(CASE_SELECT + " WHERE tenant_id=:tenantId AND id=:id")
                .param("tenantId", tenantId).param("id", id).query(this::mapCase).optional();
    }

    public List<AgentEvaluationCase> findCases(UUID tenantId) {
        return jdbcClient.sql(CASE_SELECT + " WHERE tenant_id=:tenantId ORDER BY created_at DESC, id")
                .param("tenantId", tenantId).query(this::mapCase).list();
    }

    public boolean deleteCase(UUID tenantId, UUID id) {
        return jdbcClient.sql("DELETE FROM agent_evaluation_case WHERE tenant_id=:tenantId AND id=:id")
                .param("tenantId", tenantId).param("id", id).update() == 1;
    }

    public AgentEvaluationRun insertRun(
            UUID id, UUID tenantId, AgentEvaluationCase evaluationCase, UUID taskId, String taskStatus,
            double score, boolean passed, boolean criticalFailure, Map<String, Double> dimensions,
            List<Map<String, Object>> violations, Map<String, Object> metrics, UUID createdBy) {
        jdbcClient.sql("""
                        INSERT INTO agent_evaluation_run(
                            id, tenant_id, evaluation_case_id, agent_task_id, agent_task_status,
                            total_score, passed, critical_failure, dimension_scores, violations, metrics, created_by
                        ) VALUES (
                            :id, :tenantId, :caseId, :taskId, :taskStatus, :score, :passed, :critical,
                            CAST(:dimensions AS jsonb), CAST(:violations AS jsonb), CAST(:metrics AS jsonb), :createdBy
                        )
                        """)
                .param("id", id).param("tenantId", tenantId).param("caseId", evaluationCase.id())
                .param("taskId", taskId).param("taskStatus", taskStatus).param("score", score)
                .param("passed", passed).param("critical", criticalFailure)
                .param("dimensions", toJson(dimensions)).param("violations", toJson(violations))
                .param("metrics", toJson(metrics)).param("createdBy", createdBy).update();
        return findRun(tenantId, id).orElseThrow();
    }

    public Optional<AgentEvaluationRun> findRun(UUID tenantId, UUID id) {
        return jdbcClient.sql(RUN_SELECT + " WHERE r.tenant_id=:tenantId AND r.id=:id")
                .param("tenantId", tenantId).param("id", id).query(this::mapRun).optional();
    }

    public List<AgentEvaluationRun> findRuns(UUID tenantId) {
        return jdbcClient.sql(RUN_SELECT + " WHERE r.tenant_id=:tenantId ORDER BY r.created_at DESC, r.id")
                .param("tenantId", tenantId).query(this::mapRun).list();
    }

    private AgentEvaluationCase mapCase(ResultSet rs, int row) throws SQLException {
        return new AgentEvaluationCase(
                rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class), rs.getString("name"),
                rs.getString("description"), stringList(rs.getString("required_skill_codes")),
                stringList(rs.getString("forbidden_skill_codes")), stringList(rs.getString("required_evidence_types")),
                stringList(rs.getString("forbidden_evidence_types")), rs.getInt("max_tool_calls"),
                rs.getInt("max_replans"), rs.getLong("max_duration_milliseconds"), rs.getInt("minimum_score"),
                rs.getBoolean("require_completed"), rs.getBoolean("require_human_review"),
                rs.getObject("created_by", UUID.class), rs.getObject("created_at", Instant.class));
    }

    private AgentEvaluationRun mapRun(ResultSet rs, int row) throws SQLException {
        return new AgentEvaluationRun(
                rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getObject("evaluation_case_id", UUID.class), rs.getString("evaluation_case_name"),
                rs.getObject("agent_task_id", UUID.class), rs.getString("agent_task_status"),
                rs.getDouble("total_score"), rs.getBoolean("passed"), rs.getBoolean("critical_failure"),
                doubleMap(rs.getString("dimension_scores")), objectList(rs.getString("violations")),
                objectMap(rs.getString("metrics")), rs.getObject("created_by", UUID.class),
                rs.getObject("created_at", Instant.class));
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("Unable to serialize agent evaluation payload", exception);
        }
    }

    private List<String> stringList(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<>() {});
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("Unable to read agent evaluation string list", exception);
        }
    }

    private Map<String, Double> doubleMap(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<>() {});
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("Unable to read agent evaluation dimension scores", exception);
        }
    }

    private Map<String, Object> objectMap(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<>() {});
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("Unable to read agent evaluation metrics", exception);
        }
    }

    private List<Map<String, Object>> objectList(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<>() {});
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("Unable to read agent evaluation violations", exception);
        }
    }

    private static final String CASE_SELECT = """
            SELECT id, tenant_id, name, description, required_skill_codes, forbidden_skill_codes,
                   required_evidence_types, forbidden_evidence_types, max_tool_calls, max_replans,
                   max_duration_milliseconds, minimum_score, require_completed, require_human_review,
                   created_by, created_at
            FROM agent_evaluation_case
            """;

    private static final String RUN_SELECT = """
            SELECT r.id, r.tenant_id, r.evaluation_case_id, c.name AS evaluation_case_name,
                   r.agent_task_id, r.agent_task_status, r.total_score, r.passed, r.critical_failure,
                   r.dimension_scores, r.violations, r.metrics, r.created_by, r.created_at
            FROM agent_evaluation_run r
            JOIN agent_evaluation_case c ON c.id = r.evaluation_case_id
            """;
}
