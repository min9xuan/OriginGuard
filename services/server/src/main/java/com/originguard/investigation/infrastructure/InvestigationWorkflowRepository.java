package com.originguard.investigation.infrastructure;

import com.originguard.investigation.domain.AssignableUser;
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

@Repository
public class InvestigationWorkflowRepository {
    private final JdbcClient jdbcClient;

    public InvestigationWorkflowRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
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
        jdbcClient.sql("""
                        INSERT INTO investigation_evidence(
                            id, tenant_id, case_id, asset_id, title, observation,
                            conclusion, confidence, created_by
                        ) VALUES (
                            :id, :tenantId, :caseId, :assetId, :title, :observation,
                            :conclusion, :confidence, :createdBy
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
                .param("createdBy", createdBy)
                .update();
        return findEvidenceById(tenantId, caseId, id).orElseThrow();
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
                         WHERE tenant_id = :tenantId AND case_id = :caseId
                         ORDER BY created_at DESC, id
                        """)
                .param("tenantId", tenantId)
                .param("caseId", caseId)
                .query(this::mapReview)
                .list();
    }

    public Optional<ReviewTask> findReviewTask(UUID tenantId, UUID caseId, UUID taskId) {
        return jdbcClient.sql(REVIEW_SELECT + """
                         WHERE tenant_id = :tenantId AND case_id = :caseId AND id = :taskId
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
                rs.getLong("version"),
                rs.getTimestamp("created_at").toInstant(),
                decidedAt == null ? null : decidedAt.toInstant());
    }

    private static final String EVIDENCE_SELECT = """
            SELECT id, tenant_id, case_id, asset_id, evidence_type, title, observation,
                   conclusion, confidence, created_by, created_at
            FROM investigation_evidence
            """;

    private static final String REVIEW_SELECT = """
            SELECT id, tenant_id, case_id, reviewer_id, status, decision_reason,
                   created_by, decided_by, version, created_at, decided_at
            FROM review_task
            """;
}
