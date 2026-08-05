package com.originguard.investigation.infrastructure;

import com.originguard.investigation.domain.CasePriority;
import com.originguard.investigation.domain.CaseStatus;
import com.originguard.investigation.domain.InvestigationCase;
import com.originguard.media.domain.MediaAsset;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class InvestigationCaseRepository {
    private final JdbcClient jdbcClient;

    public InvestigationCaseRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public InvestigationCase insert(
            UUID id,
            UUID tenantId,
            String caseNumber,
            String title,
            String description,
            CasePriority priority,
            UUID createdBy) {
        jdbcClient.sql("""
                        INSERT INTO investigation_case(
                            id, tenant_id, case_number, title, description, priority, created_by,
                            assigned_investigator_id
                        ) VALUES (
                            :id, :tenantId, :caseNumber, :title, :description, :priority, :createdBy,
                            :createdBy
                        )
                        """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("caseNumber", caseNumber)
                .param("title", title)
                .param("description", description)
                .param("priority", priority.name())
                .param("createdBy", createdBy)
                .update();
        return findById(tenantId, id).orElseThrow();
    }

    public Optional<InvestigationCase> findById(UUID tenantId, UUID id) {
        return jdbcClient.sql(BASE_SELECT + " WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId)
                .param("id", id)
                .query(this::map)
                .optional();
    }

    public List<InvestigationCase> findAll(UUID tenantId) {
        return jdbcClient.sql(BASE_SELECT + " WHERE tenant_id = :tenantId ORDER BY updated_at DESC, id")
                .param("tenantId", tenantId)
                .query(this::map)
                .list();
    }

    public boolean updateDetails(
            UUID tenantId,
            UUID id,
            long expectedVersion,
            String title,
            String description,
            CasePriority priority) {
        int updated = jdbcClient.sql("""
                        UPDATE investigation_case
                        SET title = :title,
                            description = :description,
                            priority = :priority,
                            version = version + 1,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE tenant_id = :tenantId AND id = :id AND version = :expectedVersion
                        """)
                .param("title", title)
                .param("description", description)
                .param("priority", priority.name())
                .param("tenantId", tenantId)
                .param("id", id)
                .param("expectedVersion", expectedVersion)
                .update();
        return updated == 1;
    }

    public boolean updateStatus(
            UUID tenantId, UUID id, long expectedVersion, CaseStatus current, CaseStatus target) {
        int updated = jdbcClient.sql("""
                        UPDATE investigation_case
                        SET status = :target,
                            version = version + 1,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE tenant_id = :tenantId
                          AND id = :id
                          AND version = :expectedVersion
                          AND status = :current
                        """)
                .param("target", target.name())
                .param("tenantId", tenantId)
                .param("id", id)
                .param("expectedVersion", expectedVersion)
                .param("current", current.name())
                .update();
        return updated == 1;
    }

    public boolean incrementVersion(UUID tenantId, UUID id, long expectedVersion) {
        int updated = jdbcClient.sql("""
                        UPDATE investigation_case
                        SET version = version + 1, updated_at = CURRENT_TIMESTAMP
                        WHERE tenant_id = :tenantId AND id = :id AND version = :expectedVersion
                        """)
                .param("tenantId", tenantId)
                .param("id", id)
                .param("expectedVersion", expectedVersion)
                .update();
        return updated == 1;
    }

    public boolean updateAssignment(
            UUID tenantId,
            UUID id,
            long expectedVersion,
            UUID investigatorId,
            UUID reviewerId) {
        int updated = jdbcClient.sql("""
                        UPDATE investigation_case
                        SET assigned_investigator_id = :investigatorId,
                            assigned_reviewer_id = :reviewerId,
                            version = version + 1,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE tenant_id = :tenantId AND id = :id AND version = :expectedVersion
                        """)
                .param("investigatorId", investigatorId)
                .param("reviewerId", reviewerId)
                .param("tenantId", tenantId)
                .param("id", id)
                .param("expectedVersion", expectedVersion)
                .update();
        return updated == 1;
    }

    public boolean isAssetLinked(UUID caseId, UUID assetId) {
        return jdbcClient.sql("""
                        SELECT EXISTS(
                            SELECT 1 FROM case_asset WHERE case_id = :caseId AND asset_id = :assetId
                        )
                        """)
                .param("caseId", caseId)
                .param("assetId", assetId)
                .query(Boolean.class)
                .single();
    }

    public void linkAsset(UUID caseId, UUID assetId, UUID addedBy) {
        jdbcClient.sql("""
                        INSERT INTO case_asset(case_id, asset_id, added_by)
                        VALUES (:caseId, :assetId, :addedBy)
                        ON CONFLICT DO NOTHING
                        """)
                .param("caseId", caseId)
                .param("assetId", assetId)
                .param("addedBy", addedBy)
                .update();
    }

    public int countAssets(UUID caseId) {
        return jdbcClient.sql("SELECT count(*) FROM case_asset WHERE case_id = :caseId")
                .param("caseId", caseId)
                .query(Integer.class)
                .single();
    }

    public List<MediaAsset> findAssets(UUID tenantId, UUID caseId) {
        return jdbcClient.sql("""
                        SELECT a.id, a.tenant_id, a.original_filename, a.content_type, a.byte_size,
                               a.sha256, a.storage_status, a.created_by, a.created_at
                        FROM media_asset a
                        JOIN case_asset ca ON ca.asset_id = a.id
                        JOIN investigation_case c ON c.id = ca.case_id
                        WHERE c.id = :caseId
                          AND c.tenant_id = :tenantId
                          AND a.tenant_id = :tenantId
                        ORDER BY ca.added_at, a.id
                        """)
                .param("caseId", caseId)
                .param("tenantId", tenantId)
                .query(this::mapAsset)
                .list();
    }

    private InvestigationCase map(ResultSet rs, int rowNum) throws SQLException {
        return new InvestigationCase(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getString("case_number"),
                rs.getString("title"),
                rs.getString("description"),
                CasePriority.valueOf(rs.getString("priority")),
                CaseStatus.valueOf(rs.getString("status")),
                rs.getObject("created_by", UUID.class),
                rs.getObject("assigned_investigator_id", UUID.class),
                rs.getObject("assigned_reviewer_id", UUID.class),
                rs.getLong("version"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private MediaAsset mapAsset(ResultSet rs, int rowNum) throws SQLException {
        return new MediaAsset(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getString("original_filename"),
                rs.getString("content_type"),
                rs.getLong("byte_size"),
                rs.getString("sha256"),
                rs.getString("storage_status"),
                rs.getObject("created_by", UUID.class),
                rs.getTimestamp("created_at").toInstant());
    }

    private static final String BASE_SELECT = """
            SELECT id, tenant_id, case_number, title, description, priority, status,
                   created_by, assigned_investigator_id, assigned_reviewer_id,
                   version, created_at, updated_at
            FROM investigation_case
            """;
}
