package com.originguard.agent.infrastructure;

import com.originguard.agent.domain.AgentCheckpoint;
import com.originguard.agent.domain.AgentKnowledgeCitation;
import com.originguard.agent.domain.AgentKnowledgeRetrieval;
import com.originguard.agent.domain.AgentObservation;
import com.originguard.agent.domain.AgentStep;
import com.originguard.agent.domain.AgentTask;
import com.originguard.agent.domain.AgentTaskStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
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
public class AgentTaskRepository {
    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public AgentTaskRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    public AgentTask insertTask(
            UUID id, UUID tenantId, UUID caseId, UUID createdBy, String goal, int stepBudget) {
        jdbcClient.sql("""
                        INSERT INTO agent_task(
                            id, tenant_id, case_id, created_by, goal, remaining_step_budget
                        ) VALUES (
                            :id, :tenantId, :caseId, :createdBy, :goal, :stepBudget
                        )
                        """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("caseId", caseId)
                .param("createdBy", createdBy)
                .param("goal", goal)
                .param("stepBudget", stepBudget)
                .update();
        return findById(tenantId, id).orElseThrow();
    }

    public Optional<AgentTask> findById(UUID tenantId, UUID taskId) {
        return jdbcClient.sql(TASK_SELECT + " WHERE tenant_id = :tenantId AND id = :taskId")
                .param("tenantId", tenantId)
                .param("taskId", taskId)
                .query(this::mapTask)
                .optional();
    }

    public List<AgentTask> findAll(UUID tenantId) {
        return jdbcClient.sql(TASK_SELECT + " WHERE tenant_id = :tenantId ORDER BY created_at DESC, id")
                .param("tenantId", tenantId)
                .query(this::mapTask)
                .list();
    }

    public boolean markRunning(UUID tenantId, UUID taskId, long expectedVersion) {
        return jdbcClient.sql("""
                        UPDATE agent_task
                        SET status = 'RUNNING', started_at = CURRENT_TIMESTAMP,
                            version = version + 1, updated_at = CURRENT_TIMESTAMP
                        WHERE tenant_id = :tenantId AND id = :taskId
                          AND status = 'PENDING' AND version = :expectedVersion
                        """)
                .param("tenantId", tenantId)
                .param("taskId", taskId)
                .param("expectedVersion", expectedVersion)
                .update() == 1;
    }

    public boolean complete(
            UUID tenantId,
            UUID taskId,
            long expectedVersion,
            String skillCode,
            String skillVersion,
            int remainingBudget,
            long checkpointVersion,
            Map<String, ?> conclusion) {
        return jdbcClient.sql("""
                        UPDATE agent_task
                        SET status = 'COMPLETED', selected_skill_code = :skillCode,
                            selected_skill_version = :skillVersion,
                            remaining_step_budget = :remainingBudget,
                            checkpoint_version = :checkpointVersion,
                            conclusion = CAST(:conclusion AS jsonb),
                            completed_at = CURRENT_TIMESTAMP,
                            version = version + 1, updated_at = CURRENT_TIMESTAMP
                        WHERE tenant_id = :tenantId AND id = :taskId
                          AND status = 'RUNNING' AND version = :expectedVersion
                        """)
                .param("skillCode", skillCode)
                .param("skillVersion", skillVersion)
                .param("remainingBudget", remainingBudget)
                .param("checkpointVersion", checkpointVersion)
                .param("conclusion", toJson(conclusion))
                .param("tenantId", tenantId)
                .param("taskId", taskId)
                .param("expectedVersion", expectedVersion)
                .update() == 1;
    }

    public void fail(UUID tenantId, UUID taskId, String code, String message) {
        jdbcClient.sql("""
                        UPDATE agent_task
                        SET status = 'FAILED', failure_code = :code, failure_message = :message,
                            completed_at = CURRENT_TIMESTAMP,
                            version = version + 1, updated_at = CURRENT_TIMESTAMP
                        WHERE tenant_id = :tenantId AND id = :taskId AND status = 'RUNNING'
                        """)
                .param("code", code)
                .param("message", message == null ? "Agent execution failed" : message)
                .param("tenantId", tenantId)
                .param("taskId", taskId)
                .update();
    }

    public boolean cancel(UUID tenantId, UUID taskId, long expectedVersion) {
        return jdbcClient.sql("""
                        UPDATE agent_task
                        SET status = 'CANCELLED', completed_at = CURRENT_TIMESTAMP,
                            version = version + 1, updated_at = CURRENT_TIMESTAMP
                        WHERE tenant_id = :tenantId AND id = :taskId
                          AND status = 'PENDING' AND version = :expectedVersion
                        """)
                .param("tenantId", tenantId)
                .param("taskId", taskId)
                .param("expectedVersion", expectedVersion)
                .update() == 1;
    }

    public AgentStep appendStep(
            UUID tenantId,
            UUID taskId,
            String stepType,
            String status,
            String skillCode,
            String toolCode,
            Map<String, ?> input,
            Map<String, ?> output) {
        int sequence = jdbcClient.sql("""
                        SELECT COALESCE(max(sequence_number), 0) + 1
                        FROM agent_step WHERE tenant_id = :tenantId AND task_id = :taskId
                        """)
                .param("tenantId", tenantId)
                .param("taskId", taskId)
                .query(Integer.class)
                .single();
        UUID id = UUID.randomUUID();
        jdbcClient.sql("""
                        INSERT INTO agent_step(
                            id, tenant_id, task_id, sequence_number, step_type, status,
                            skill_code, tool_code, input, output
                        ) VALUES (
                            :id, :tenantId, :taskId, :sequence, :stepType, :status,
                            :skillCode, :toolCode, CAST(:input AS jsonb), CAST(:output AS jsonb)
                        )
                        """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("taskId", taskId)
                .param("sequence", sequence)
                .param("stepType", stepType)
                .param("status", status)
                .param("skillCode", skillCode == null ? "" : skillCode)
                .param("toolCode", toolCode == null ? "" : toolCode)
                .param("input", toJson(input))
                .param("output", toJson(output))
                .update();
        return findStep(tenantId, taskId, id).orElseThrow();
    }

    public AgentObservation insertObservation(
            UUID tenantId,
            UUID taskId,
            UUID caseId,
            UUID assetId,
            String evidenceType,
            String summary,
            Map<String, ?> payload) {
        int sequence = jdbcClient.sql("""
                        SELECT COALESCE(max(sequence_number), 0) + 1
                        FROM agent_observation WHERE tenant_id = :tenantId AND task_id = :taskId
                        """)
                .param("tenantId", tenantId)
                .param("taskId", taskId)
                .query(Integer.class)
                .single();
        UUID id = UUID.randomUUID();
        jdbcClient.sql("""
                        INSERT INTO agent_observation(
                            id, tenant_id, task_id, case_id, asset_id, sequence_number,
                            evidence_type, summary, payload
                        ) VALUES (
                            :id, :tenantId, :taskId, :caseId, :assetId, :sequence,
                            :evidenceType, :summary, CAST(:payload AS jsonb)
                        )
                        """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("taskId", taskId)
                .param("caseId", caseId)
                .param("assetId", assetId)
                .param("sequence", sequence)
                .param("evidenceType", evidenceType)
                .param("summary", summary)
                .param("payload", toJson(payload))
                .update();
        return findObservation(tenantId, taskId, id).orElseThrow();
    }

    public AgentKnowledgeRetrieval insertKnowledgeRetrieval(
            UUID tenantId,
            UUID taskId,
            UUID caseId,
            String skillCode,
            String toolCode,
            String query,
            String retrievalMode,
            String embeddingProvider,
            boolean knowledgeAvailable,
            List<Map<String, Object>> citations) {
        UUID retrievalId = UUID.randomUUID();
        jdbcClient.sql("""
                        INSERT INTO agent_knowledge_retrieval(
                            id, tenant_id, task_id, case_id, skill_code, tool_code, query,
                            retrieval_mode, embedding_provider, knowledge_available
                        ) VALUES (
                            :id, :tenantId, :taskId, :caseId, :skillCode, :toolCode, :query,
                            :retrievalMode, :embeddingProvider, :knowledgeAvailable
                        )
                        """)
                .param("id", retrievalId).param("tenantId", tenantId).param("taskId", taskId)
                .param("caseId", caseId).param("skillCode", skillCode).param("toolCode", toolCode)
                .param("query", query).param("retrievalMode", retrievalMode)
                .param("embeddingProvider", embeddingProvider).param("knowledgeAvailable", knowledgeAvailable)
                .update();
        for (int index = 0; index < citations.size(); index++) {
            Map<String, Object> citation = citations.get(index);
            jdbcClient.sql("""
                            INSERT INTO agent_knowledge_citation(
                                id, tenant_id, retrieval_id, document_id, chunk_id, document_title,
                                document_type, document_version, chunk_index, quote, semantic_score,
                                keyword_score, hybrid_score, citation_order
                            ) VALUES (
                                :id, :tenantId, :retrievalId, :documentId, :chunkId, :documentTitle,
                                :documentType, :documentVersion, :chunkIndex, :quote, :semanticScore,
                                :keywordScore, :hybridScore, :citationOrder
                            )
                            """)
                    .param("id", UUID.randomUUID()).param("tenantId", tenantId).param("retrievalId", retrievalId)
                    .param("documentId", UUID.fromString(String.valueOf(citation.get("documentId"))))
                    .param("chunkId", UUID.fromString(String.valueOf(citation.get("chunkId"))))
                    .param("documentTitle", String.valueOf(citation.get("documentTitle")))
                    .param("documentType", String.valueOf(citation.get("documentType")))
                    .param("documentVersion", number(citation, "documentVersion").intValue())
                    .param("chunkIndex", number(citation, "chunkIndex").intValue())
                    .param("quote", String.valueOf(citation.get("quote")))
                    .param("semanticScore", number(citation, "semanticScore").doubleValue())
                    .param("keywordScore", number(citation, "keywordScore").doubleValue())
                    .param("hybridScore", number(citation, "hybridScore").doubleValue())
                    .param("citationOrder", index + 1).update();
        }
        return findKnowledgeRetrieval(tenantId, taskId, retrievalId).orElseThrow();
    }

    public AgentCheckpoint insertCheckpoint(
            UUID tenantId, UUID taskId, long checkpointVersion, Map<String, ?> state) {
        UUID id = UUID.randomUUID();
        jdbcClient.sql("""
                        INSERT INTO agent_checkpoint(
                            id, tenant_id, task_id, checkpoint_version, state
                        ) VALUES (
                            :id, :tenantId, :taskId, :checkpointVersion, CAST(:state AS jsonb)
                        )
                        """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("taskId", taskId)
                .param("checkpointVersion", checkpointVersion)
                .param("state", toJson(state))
                .update();
        return findCheckpoint(tenantId, taskId, id).orElseThrow();
    }

    public List<AgentStep> findSteps(UUID tenantId, UUID taskId) {
        return jdbcClient.sql("""
                        SELECT id, task_id, sequence_number, step_type, status, skill_code,
                               tool_code, input::text AS input, output::text AS output, created_at
                        FROM agent_step
                        WHERE tenant_id = :tenantId AND task_id = :taskId
                        ORDER BY sequence_number
                        """)
                .param("tenantId", tenantId)
                .param("taskId", taskId)
                .query(this::mapStep)
                .list();
    }

    public List<AgentObservation> findObservations(UUID tenantId, UUID taskId) {
        return jdbcClient.sql("""
            SELECT id, task_id, case_id, asset_id, evidence_type, summary,
                   payload::text AS payload, created_at
                        FROM agent_observation
                        WHERE tenant_id = :tenantId AND task_id = :taskId
            ORDER BY sequence_number
                        """)
                .param("tenantId", tenantId)
                .param("taskId", taskId)
                .query(this::mapObservation)
                .list();
    }

    public List<AgentCheckpoint> findCheckpoints(UUID tenantId, UUID taskId) {
        return jdbcClient.sql("""
                        SELECT id, task_id, checkpoint_version, state::text AS state, created_at
                        FROM agent_checkpoint
                        WHERE tenant_id = :tenantId AND task_id = :taskId
                        ORDER BY checkpoint_version
                        """)
                .param("tenantId", tenantId)
                .param("taskId", taskId)
                .query(this::mapCheckpoint)
                .list();
    }

    public List<AgentKnowledgeRetrieval> findKnowledgeRetrievals(UUID tenantId, UUID taskId) {
        return jdbcClient.sql("""
                        SELECT id, task_id, case_id, skill_code, tool_code, query, retrieval_mode,
                               embedding_provider, knowledge_available, created_at
                        FROM agent_knowledge_retrieval
                        WHERE tenant_id = :tenantId AND task_id = :taskId
                        ORDER BY created_at, id
                        """)
                .param("tenantId", tenantId).param("taskId", taskId)
                .query((rs, row) -> mapKnowledgeRetrieval(tenantId, rs)).list();
    }

    private Optional<AgentStep> findStep(UUID tenantId, UUID taskId, UUID id) {
        return jdbcClient.sql("""
                        SELECT id, task_id, sequence_number, step_type, status, skill_code,
                               tool_code, input::text AS input, output::text AS output, created_at
                        FROM agent_step
                        WHERE tenant_id = :tenantId AND task_id = :taskId AND id = :id
                        """)
                .param("tenantId", tenantId).param("taskId", taskId).param("id", id)
                .query(this::mapStep).optional();
    }

    public Optional<AgentObservation> findObservation(UUID tenantId, UUID taskId, UUID id) {
        return jdbcClient.sql("""
                        SELECT id, task_id, case_id, asset_id, evidence_type, summary,
                               payload::text AS payload, created_at
                        FROM agent_observation
                        WHERE tenant_id = :tenantId AND task_id = :taskId AND id = :id
                        """)
                .param("tenantId", tenantId).param("taskId", taskId).param("id", id)
                .query(this::mapObservation).optional();
    }

    private Optional<AgentCheckpoint> findCheckpoint(UUID tenantId, UUID taskId, UUID id) {
        return jdbcClient.sql("""
                        SELECT id, task_id, checkpoint_version, state::text AS state, created_at
                        FROM agent_checkpoint
                        WHERE tenant_id = :tenantId AND task_id = :taskId AND id = :id
                        """)
                .param("tenantId", tenantId).param("taskId", taskId).param("id", id)
                .query(this::mapCheckpoint).optional();
    }

    private Optional<AgentKnowledgeRetrieval> findKnowledgeRetrieval(UUID tenantId, UUID taskId, UUID id) {
        return jdbcClient.sql("""
                        SELECT id, task_id, case_id, skill_code, tool_code, query, retrieval_mode,
                               embedding_provider, knowledge_available, created_at
                        FROM agent_knowledge_retrieval
                        WHERE tenant_id = :tenantId AND task_id = :taskId AND id = :id
                        """)
                .param("tenantId", tenantId).param("taskId", taskId).param("id", id)
                .query((rs, row) -> mapKnowledgeRetrieval(tenantId, rs)).optional();
    }

    private AgentKnowledgeRetrieval mapKnowledgeRetrieval(UUID tenantId, ResultSet rs) throws SQLException {
        UUID retrievalId = rs.getObject("id", UUID.class);
        return new AgentKnowledgeRetrieval(
                retrievalId, rs.getObject("task_id", UUID.class), rs.getObject("case_id", UUID.class),
                rs.getString("skill_code"), rs.getString("tool_code"), rs.getString("query"),
                rs.getString("retrieval_mode"), rs.getString("embedding_provider"),
                rs.getBoolean("knowledge_available"), findCitations(tenantId, retrievalId),
                rs.getTimestamp("created_at").toInstant());
    }

    private List<AgentKnowledgeCitation> findCitations(UUID tenantId, UUID retrievalId) {
        return jdbcClient.sql("""
                        SELECT id, document_id, chunk_id, document_title, document_type,
                               document_version, chunk_index, quote, semantic_score,
                               keyword_score, hybrid_score, citation_order
                        FROM agent_knowledge_citation
                        WHERE tenant_id = :tenantId AND retrieval_id = :retrievalId
                        ORDER BY citation_order
                        """)
                .param("tenantId", tenantId).param("retrievalId", retrievalId)
                .query((rs, row) -> new AgentKnowledgeCitation(
                        rs.getObject("id", UUID.class), rs.getObject("document_id", UUID.class),
                        rs.getObject("chunk_id", UUID.class), rs.getString("document_title"),
                        rs.getString("document_type"), rs.getInt("document_version"),
                        rs.getInt("chunk_index"), rs.getString("quote"), rs.getDouble("semantic_score"),
                        rs.getDouble("keyword_score"), rs.getDouble("hybrid_score"),
                        rs.getInt("citation_order"))).list();
    }

    private AgentTask mapTask(ResultSet rs, int rowNum) throws SQLException {
        return new AgentTask(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("case_id", UUID.class),
                rs.getObject("created_by", UUID.class),
                AgentTaskStatus.valueOf(rs.getString("status")),
                rs.getString("goal"),
                rs.getString("selected_skill_code"),
                rs.getString("selected_skill_version"),
                rs.getInt("remaining_step_budget"),
                readJsonNullable(rs.getString("conclusion")),
                rs.getString("failure_code"),
                rs.getString("failure_message"),
                rs.getLong("checkpoint_version"),
                rs.getLong("version"),
                rs.getTimestamp("created_at").toInstant(),
                instant(rs.getTimestamp("started_at")),
                instant(rs.getTimestamp("completed_at")),
                rs.getTimestamp("updated_at").toInstant());
    }

    private AgentStep mapStep(ResultSet rs, int rowNum) throws SQLException {
        return new AgentStep(
                rs.getObject("id", UUID.class), rs.getObject("task_id", UUID.class),
                rs.getInt("sequence_number"), rs.getString("step_type"), rs.getString("status"),
                rs.getString("skill_code"), rs.getString("tool_code"),
                readJson(rs.getString("input")), readJson(rs.getString("output")),
                rs.getTimestamp("created_at").toInstant());
    }

    private AgentObservation mapObservation(ResultSet rs, int rowNum) throws SQLException {
        return new AgentObservation(
                rs.getObject("id", UUID.class), rs.getObject("task_id", UUID.class),
                rs.getObject("case_id", UUID.class), rs.getObject("asset_id", UUID.class),
                rs.getString("evidence_type"), rs.getString("summary"),
                readJson(rs.getString("payload")), rs.getTimestamp("created_at").toInstant());
    }

    private AgentCheckpoint mapCheckpoint(ResultSet rs, int rowNum) throws SQLException {
        return new AgentCheckpoint(
                rs.getObject("id", UUID.class), rs.getObject("task_id", UUID.class),
                rs.getLong("checkpoint_version"), readJson(rs.getString("state")),
                rs.getTimestamp("created_at").toInstant());
    }

    private Map<String, Object> readJson(String json) throws SQLException {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JacksonException exception) {
            throw new SQLException("Invalid agent JSON", exception);
        }
    }

    private Map<String, Object> readJsonNullable(String json) throws SQLException {
        return json == null ? Map.of() : readJson(json);
    }

    private String toJson(Map<String, ?> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("Agent payload is not serializable", exception);
        }
    }

    private Number number(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (value instanceof Number number) return number;
        return Double.valueOf(String.valueOf(value));
    }

    private Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static final String TASK_SELECT = """
            SELECT id, tenant_id, case_id, created_by, status, goal,
                   selected_skill_code, selected_skill_version, remaining_step_budget,
                   conclusion::text AS conclusion, failure_code, failure_message,
                   checkpoint_version, version, created_at, started_at, completed_at, updated_at
            FROM agent_task
            """;
}
