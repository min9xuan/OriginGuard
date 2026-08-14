package com.originguard.investigation.infrastructure;

import com.originguard.investigation.domain.AssignableUser;
import com.originguard.investigation.domain.AgentEvidenceCandidate;
import com.originguard.investigation.domain.EvidenceConclusion;
import com.originguard.investigation.domain.EvidenceConfidence;
import com.originguard.investigation.domain.InvestigationEvidence;
import com.originguard.investigation.domain.ReviewStatus;
import com.originguard.investigation.domain.ReviewTask;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Repository
public class InvestigationWorkflowRepository {
    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public InvestigationWorkflowRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    public List<AssignableUser> findAssignableUsers(UUID tenantId) {
        return jdbcClient.sql("""
                        SELECT u.id, u.username, u.display_name, r.code AS role
                        FROM sys_user u
                        JOIN sys_user_role ur ON ur.user_id = u.id
                        JOIN sys_role r ON r.id = ur.role_id
                        WHERE u.tenant_id = :tenantId
                          AND u.enabled = TRUE
                          AND r.code IN ('INVESTIGATOR', 'REVIEWER')
                        ORDER BY r.code, u.display_name, u.id
                        """)
                .param("tenantId", tenantId)
                .query((rs, rowNum) -> new AssignableUser(
                        rs.getObject("id", UUID.class),
                        rs.getString("username"),
                        rs.getString("display_name"),
                        rs.getString("role")))
                .list();
    }

    public boolean userHasRole(UUID tenantId, UUID userId, String role) {
        return jdbcClient.sql("""
                        SELECT EXISTS(
                            SELECT 1
                            FROM sys_user u
                            JOIN sys_user_role ur ON ur.user_id = u.id
                            JOIN sys_role r ON r.id = ur.role_id
                            WHERE u.tenant_id = :tenantId
                              AND u.id = :userId
                              AND u.enabled = TRUE
                              AND r.code = :role
                        )
                        """)
                .param("tenantId", tenantId)
                .param("userId", userId)
                .param("role", role)
                .query(Boolean.class)
                .single();
    }

    public void insertAssignment(
            UUID tenantId, UUID caseId, UUID investigatorId, UUID reviewerId, UUID assignedBy) {
        jdbcClient.sql("""
                        INSERT INTO case_assignment(
                            tenant_id, case_id, investigator_id, reviewer_id, assigned_by
                        ) VALUES (
                            :tenantId, :caseId, :investigatorId, :reviewerId, :assignedBy
                        )
                        """)
                .param("tenantId", tenantId)
                .param("caseId", caseId)
                .param("investigatorId", investigatorId)
                .param("reviewerId", reviewerId)
                .param("assignedBy", assignedBy)
                .update();
    }

    public InvestigationEvidence insertEvidence(
            UUID id,
            UUID tenantId,
            UUID caseId,
            UUID assetId,
            String title,
            String observation,
            EvidenceConclusion conclusion,
            EvidenceConfidence confidence,
            UUID createdBy) {
        return insertEvidence(id, tenantId, caseId, assetId, "HUMAN_OBSERVATION", null,
                title, observation, conclusion, confidence, createdBy);
    }

    public InvestigationEvidence insertAgentEvidence(
            UUID id, UUID tenantId, UUID caseId, AgentEvidenceCandidate candidate,
            String title, String observation, UUID createdBy) {
        return insertEvidence(
                id, tenantId, caseId, candidate.assetId(), "AGENT_OBSERVATION", candidate.observationId(),
                title, observation, EvidenceConclusion.INCONCLUSIVE, EvidenceConfidence.LOW, createdBy);
    }

    private InvestigationEvidence insertEvidence(
            UUID id, UUID tenantId, UUID caseId, UUID assetId, String evidenceType,
            UUID sourceObservationId, String title, String observation,
            EvidenceConclusion conclusion, EvidenceConfidence confidence, UUID createdBy) {
        jdbcClient.sql("""
                        INSERT INTO investigation_evidence(
                            id, tenant_id, case_id, asset_id, title, observation,
                            conclusion, confidence, evidence_type, source_observation_id, created_by
                        ) VALUES (
                            :id, :tenantId, :caseId, :assetId, :title, :observation,
                            :conclusion, :confidence, :evidenceType, :sourceObservationId, :createdBy
                        )
                        """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("caseId", caseId)
                .param("assetId", assetId)
                .param("title", title)
                .param("observation", observation)
                .param("conclusion", conclusion.name())
                .param("confidence", confidence.name())
                .param("evidenceType", evidenceType)
                .param("sourceObservationId", sourceObservationId)
                .param("createdBy", createdBy)
                .update();
        return findEvidenceById(tenantId, caseId, id).orElseThrow();
    }

    public List<AgentEvidenceCandidate> findAgentEvidenceCandidates(UUID tenantId, UUID caseId) {
        return jdbcClient.sql("""
                        SELECT o.id AS observation_id, o.task_id, o.asset_id, o.evidence_type,
                               o.summary, o.payload::text AS payload, e.id AS promoted_evidence_id,
                               o.created_at
                        FROM agent_observation o
                        JOIN agent_task t ON t.id = o.task_id AND t.tenant_id = o.tenant_id
                        LEFT JOIN investigation_evidence e
                          ON e.source_observation_id = o.id AND e.tenant_id = o.tenant_id
                        WHERE o.tenant_id = :tenantId
                            AND o.case_id = :caseId
                            AND t.status = 'COMPLETED'
                            AND o.evidence_type <> 'LEGACY_RAG_GUIDANCE'
                        ORDER BY o.created_at, o.sequence_number
                        """)
                .param("tenantId", tenantId)
                .param("caseId", caseId)
                .query((rs, rowNum) -> new AgentEvidenceCandidate(
                        rs.getObject("observation_id", UUID.class),
                        rs.getObject("task_id", UUID.class),
                        rs.getObject("asset_id", UUID.class),
                        rs.getString("evidence_type"),
                        rs.getString("summary"),
                        readJson(rs.getString("payload")),
                        rs.getObject("promoted_evidence_id", UUID.class),
                        rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    public Optional<AgentEvidenceCandidate> findAgentEvidenceCandidate(
            UUID tenantId, UUID caseId, UUID observationId) {
        return findAgentEvidenceCandidates(tenantId, caseId).stream()
                .filter(candidate -> candidate.observationId().equals(observationId))
                .findFirst();
    }

    public List<InvestigationEvidence> findEvidence(UUID tenantId, UUID caseId) {
        return jdbcClient.sql(EVIDENCE_SELECT + """
                         WHERE tenant_id = :tenantId AND case_id = :caseId
                         ORDER BY created_at, id
                        """)
                .param("tenantId", tenantId)
                .param("caseId", caseId)
                .query(this::mapEvidence)
                .list();
    }

    public int countEvidence(UUID tenantId, UUID caseId) {
        return jdbcClient.sql("""
                        SELECT count(*) FROM investigation_evidence
                        WHERE tenant_id = :tenantId AND case_id = :caseId
                        """)
                .param("tenantId", tenantId)
                .param("caseId", caseId)
                .query(Integer.class)
                .single();
    }

    public boolean isAssetLinked(UUID tenantId, UUID caseId, UUID assetId) {
        return jdbcClient.sql("""
                        SELECT EXISTS(
                            SELECT 1
                            FROM case_asset ca
                            JOIN investigation_case c ON c.id = ca.case_id
                            JOIN media_asset a ON a.id = ca.asset_id
                            WHERE c.tenant_id = :tenantId
                              AND a.tenant_id = :tenantId
                              AND ca.case_id = :caseId
                              AND ca.asset_id = :assetId
                        )
                        """)
                .param("tenantId", tenantId)
                .param("caseId", caseId)
                .param("assetId", assetId)
                .query(Boolean.class)
                .single();
    }

    public ReviewTask insertReviewTask(
            UUID id, UUID tenantId, UUID caseId, UUID reviewerId, UUID createdBy) {
        jdbcClient.sql("""
                        INSERT INTO review_task(id, tenant_id, case_id, reviewer_id, created_by)
                        VALUES (:id, :tenantId, :caseId, :reviewerId, :createdBy)
                        """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("caseId", caseId)
                .param("reviewerId", reviewerId)
                .param("createdBy", createdBy)
                .update();
        return findReviewTask(tenantId, caseId, id).orElseThrow();
    }

    public List<ReviewTask> findReviewTasks(UUID tenantId, UUID caseId) {
        return jdbcClient.sql(REVIEW_SELECT + """
                         WHERE r.tenant_id = :tenantId AND r.case_id = :caseId
                         ORDER BY r.created_at DESC, r.id
                        """)
                .param("tenantId", tenantId)
                .param("caseId", caseId)
                .query(this::mapReview)
                .list();
    }

    public Optional<ReviewTask> findReviewTask(UUID tenantId, UUID caseId, UUID taskId) {
        return jdbcClient.sql(REVIEW_SELECT + """
                         WHERE r.tenant_id = :tenantId AND r.case_id = :caseId AND r.id = :taskId
                        """)
                .param("tenantId", tenantId)
                .param("caseId", caseId)
                .param("taskId", taskId)
                .query(this::mapReview)
                .optional();
    }

    public boolean decideReview(
            UUID tenantId,
            UUID caseId,
            UUID taskId,
            UUID reviewerId,
            long expectedVersion,
            ReviewStatus decision,
            String reason) {
        int updated = jdbcClient.sql("""
                        UPDATE review_task
                        SET status = :decision,
                            decision_reason = :reason,
                            decided_by = :reviewerId,
                            decided_at = CURRENT_TIMESTAMP,
                            version = version + 1
                        WHERE tenant_id = :tenantId
                          AND case_id = :caseId
                          AND id = :taskId
                          AND reviewer_id = :reviewerId
                          AND status = 'PENDING'
                          AND version = :expectedVersion
                        """)
                .param("decision", decision.name())
                .param("reason", reason)
                .param("reviewerId", reviewerId)
                .param("tenantId", tenantId)
                .param("caseId", caseId)
                .param("taskId", taskId)
                .param("expectedVersion", expectedVersion)
                .update();
        return updated == 1;
    }

    public boolean evidenceBelongsToCase(UUID tenantId, UUID caseId, List<UUID> evidenceIds) {
        if (evidenceIds.isEmpty()) return false;
        int count = jdbcClient.sql("""
                        SELECT count(*) FROM investigation_evidence
                        WHERE tenant_id = :tenantId AND case_id = :caseId AND id IN (:evidenceIds)
                        """)
                .param("tenantId", tenantId)
                .param("caseId", caseId)
                .param("evidenceIds", evidenceIds)
                .query(Integer.class)
                .single();
        return count == evidenceIds.size();
    }

    public void replaceReviewEvidenceReferences(
            UUID tenantId, UUID reviewTaskId, List<UUID> evidenceIds) {
        jdbcClient.sql("DELETE FROM review_evidence_reference WHERE tenant_id = :tenantId AND review_task_id = :taskId")
                .param("tenantId", tenantId).param("taskId", reviewTaskId).update();
        for (UUID evidenceId : evidenceIds) {
            jdbcClient.sql("""
                            INSERT INTO review_evidence_reference(tenant_id, review_task_id, evidence_id)
                            VALUES (:tenantId, :taskId, :evidenceId)
                            """)
                    .param("tenantId", tenantId).param("taskId", reviewTaskId)
                    .param("evidenceId", evidenceId).update();
        }
    }

    private Optional<InvestigationEvidence> findEvidenceById(UUID tenantId, UUID caseId, UUID id) {
        return jdbcClient.sql(EVIDENCE_SELECT + """
                         WHERE tenant_id = :tenantId AND case_id = :caseId AND id = :id
                        """)
                .param("tenantId", tenantId)
                .param("caseId", caseId)
                .param("id", id)
                .query(this::mapEvidence)
                .optional();
    }

    private InvestigationEvidence mapEvidence(ResultSet rs, int rowNum) throws SQLException {
        return new InvestigationEvidence(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("case_id", UUID.class),
                rs.getObject("asset_id", UUID.class),
                rs.getString("evidence_type"),
                rs.getString("title"),
                rs.getString("observation"),
                EvidenceConclusion.valueOf(rs.getString("conclusion")),
                EvidenceConfidence.valueOf(rs.getString("confidence")),
                rs.getObject("source_observation_id", UUID.class),
                rs.getObject("created_by", UUID.class),
                rs.getTimestamp("created_at").toInstant());
    }

    private ReviewTask mapReview(ResultSet rs, int rowNum) throws SQLException {
        Timestamp decidedAt = rs.getTimestamp("decided_at");
        return new ReviewTask(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("case_id", UUID.class),
                rs.getObject("reviewer_id", UUID.class),
                ReviewStatus.valueOf(rs.getString("status")),
                rs.getString("decision_reason"),
                rs.getObject("created_by", UUID.class),
                rs.getObject("decided_by", UUID.class),
                java.util.Arrays.asList((UUID[]) rs.getArray("cited_evidence_ids").getArray()),
                rs.getLong("version"),
                rs.getTimestamp("created_at").toInstant(),
                decidedAt == null ? null : decidedAt.toInstant());
    }

    private static final String EVIDENCE_SELECT = """
            SELECT id, tenant_id, case_id, asset_id, evidence_type, title, observation,
                   conclusion, confidence, source_observation_id, created_by, created_at
            FROM investigation_evidence
            """;

    private static final String REVIEW_SELECT = """
            SELECT r.id, r.tenant_id, r.case_id, r.reviewer_id, r.status, r.decision_reason,
                   r.created_by, r.decided_by, r.version, r.created_at, r.decided_at,
                   ARRAY(
                       SELECT ref.evidence_id
                       FROM review_evidence_reference ref
                       WHERE ref.tenant_id = r.tenant_id AND ref.review_task_id = r.id
                       ORDER BY ref.created_at, ref.evidence_id
                   ) AS cited_evidence_ids
            FROM review_task r
            """;

    private java.util.Map<String, Object> readJson(String json) throws SQLException {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JacksonException exception) {
            throw new SQLException("Invalid observation payload", exception);
        }
    }
}
