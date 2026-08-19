package com.originguard.knowledge.infrastructure;

import com.originguard.knowledge.domain.KnowledgeDocument;
import com.originguard.knowledge.domain.KnowledgeSearchResult;
import com.originguard.knowledge.domain.RagEvaluationCase;
import com.originguard.knowledge.domain.RagEvaluationCaseResult;
import com.originguard.knowledge.domain.RagEvaluationRun;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class KnowledgeRepository {
    private static final String DOCUMENT_SELECT = """
            SELECT id, tenant_id, title, document_type, content, status, published_version,
                   source_scope, source_priority, source_provider, source_identifier,
                   source_url, source_venue, source_year,
                   created_by, updated_by, version, created_at, updated_at, published_at
            FROM knowledge_document
            """;
    private final JdbcClient jdbcClient;

    public KnowledgeRepository(JdbcClient jdbcClient) { this.jdbcClient = jdbcClient; }

    public KnowledgeDocument insert(UUID id, UUID tenantId, String title, String type, String content, UUID actorId) {
        jdbcClient.sql("""
                        INSERT INTO knowledge_document(
                            id, tenant_id, title, document_type, content, created_by, updated_by
                        ) VALUES (:id, :tenantId, :title, :type, :content, :actorId, :actorId)
                        """)
                .param("id", id).param("tenantId", tenantId).param("title", title)
                .param("type", type).param("content", content).param("actorId", actorId).update();
        return findById(tenantId, id).orElseThrow();
    }

    public KnowledgeDocument insertExternalDraft(
            UUID id, UUID tenantId, String title, String type, String content, UUID actorId,
            String provider, String identifier, String sourceUrl, String venue, int year) {
        jdbcClient.sql("""
                        INSERT INTO knowledge_document(
                            id, tenant_id, title, document_type, content, source_scope, source_priority,
                            source_provider, source_identifier, source_url, source_venue, source_year,
                            created_by, updated_by
                        ) VALUES (
                            :id, :tenantId, :title, :type, :content, 'EXTERNAL', 40,
                            :provider, :identifier, :sourceUrl, :venue, :year, :actorId, :actorId
                        )
                        """)
                .param("id", id).param("tenantId", tenantId).param("title", title)
                .param("type", type).param("content", content).param("provider", provider)
                .param("identifier", identifier).param("sourceUrl", sourceUrl)
                .param("venue", venue).param("year", year).param("actorId", actorId).update();
        return findById(tenantId, id).orElseThrow();
    }

    public boolean externalSourceExists(UUID tenantId, String provider, String identifier) {
        return jdbcClient.sql("""
                        SELECT EXISTS(
                            SELECT 1 FROM knowledge_document
                            WHERE tenant_id = :tenantId AND source_provider = :provider
                              AND source_identifier = :identifier
                        )
                        """)
                .param("tenantId", tenantId).param("provider", provider).param("identifier", identifier)
                .query(Boolean.class).single();
    }

    public Optional<KnowledgeDocument> findById(UUID tenantId, UUID id) {
        return jdbcClient.sql(DOCUMENT_SELECT + " WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId).param("id", id).query(this::mapDocument).optional();
    }

    public List<KnowledgeDocument> findAll(UUID tenantId, boolean includeDrafts) {
        String status = includeDrafts ? "" : " AND status = 'PUBLISHED'";
        return jdbcClient.sql(DOCUMENT_SELECT + " WHERE tenant_id = :tenantId" + status
                        + " ORDER BY updated_at DESC, id")
                .param("tenantId", tenantId).query(this::mapDocument).list();
    }

    public boolean updateDraft(UUID tenantId, UUID id, long expectedVersion, String title, String type,
                               String content, UUID actorId) {
        return jdbcClient.sql("""
                        UPDATE knowledge_document
                        SET title = :title, document_type = :type, content = :content,
                            updated_by = :actorId, version = version + 1, updated_at = CURRENT_TIMESTAMP
                        WHERE tenant_id = :tenantId AND id = :id AND version = :expectedVersion
                          AND status = 'DRAFT'
                        """)
                .param("title", title).param("type", type).param("content", content).param("actorId", actorId)
                .param("tenantId", tenantId).param("id", id).param("expectedVersion", expectedVersion)
                .update() == 1;
    }

    public void insertChunk(UUID id, UUID tenantId, UUID documentId, int documentVersion,
                            int chunkIndex, String content, String provider, int dimensions, String embedding) {
        jdbcClient.sql("""
                        INSERT INTO knowledge_chunk(
                            id, tenant_id, document_id, document_version, chunk_index,
                            content, character_count
                        ) VALUES (
                            :id, :tenantId, :documentId, :documentVersion, :chunkIndex,
                            :content, :characterCount
                        )
                        """)
                .param("id", id).param("tenantId", tenantId).param("documentId", documentId)
                .param("documentVersion", documentVersion).param("chunkIndex", chunkIndex)
                .param("content", content).param("characterCount", content.length()).update();
        upsertEmbedding(id, tenantId, provider, dimensions, embedding);
    }

    public void upsertEmbedding(
            UUID chunkId, UUID tenantId, String provider, int dimensions, String embedding) {
        jdbcClient.sql("""
                        INSERT INTO knowledge_chunk_embedding(
                            chunk_id, tenant_id, provider_code, dimensions, embedding
                        ) VALUES (
                            :chunkId, :tenantId, :provider, :dimensions, CAST(:embedding AS vector)
                        )
                        ON CONFLICT (chunk_id, provider_code) DO UPDATE
                        SET dimensions = EXCLUDED.dimensions,
                            embedding = EXCLUDED.embedding,
                            updated_at = CURRENT_TIMESTAMP
                        """)
                .param("chunkId", chunkId).param("tenantId", tenantId).param("provider", provider)
                .param("dimensions", dimensions).param("embedding", embedding).update();
    }

    public boolean markPublished(UUID tenantId, UUID id, long expectedVersion, UUID actorId) {
        return jdbcClient.sql("""
                        UPDATE knowledge_document
                        SET status = 'PUBLISHED', published_version = published_version + 1,
                            updated_by = :actorId, version = version + 1,
                            updated_at = CURRENT_TIMESTAMP, published_at = CURRENT_TIMESTAMP
                        WHERE tenant_id = :tenantId AND id = :id AND version = :expectedVersion
                          AND status = 'DRAFT'
                        """)
                .param("actorId", actorId).param("tenantId", tenantId).param("id", id)
                .param("expectedVersion", expectedVersion).update() == 1;
    }

    public List<KnowledgeSearchResult> search(
            UUID tenantId, String query, String provider, int dimensions, String embedding, int limit) {
        if (dimensions != 64 && dimensions != 512) {
            throw new IllegalArgumentException("Unsupported indexed embedding dimension: " + dimensions);
        }
        String vectorType = "vector(" + dimensions + ")";
        String sql = """
                        WITH scored AS (
                            SELECT d.id AS document_id, d.title, d.document_type, d.source_priority,
                                   c.document_version, c.id AS chunk_id, c.chunk_index, c.content,
                                   GREATEST(0.0, 1.0 - (e.embedding::%s <=> CAST(:embedding AS %s))) AS semantic_score,
                                   CASE WHEN c.search_vector @@ websearch_to_tsquery('simple', :query)
                                        THEN ts_rank_cd(c.search_vector, websearch_to_tsquery('simple', :query))
                                        ELSE 0.0 END AS keyword_score
                            FROM knowledge_chunk c
                            JOIN knowledge_chunk_embedding e ON e.chunk_id = c.id
                                AND e.tenant_id = :tenantId
                                AND e.provider_code = :provider
                                AND e.dimensions = :dimensions
                            JOIN knowledge_document d ON d.id = c.document_id
                            WHERE c.tenant_id = :tenantId AND d.tenant_id = :tenantId
                              AND d.status = 'PUBLISHED'
                              AND c.document_version = d.published_version
                        )
                        SELECT *,
                               ((semantic_score * 0.70 + LEAST(keyword_score, 1.0) * 0.30) * 0.85
                                + (source_priority::double precision / 100.0) * 0.15) AS hybrid_score
                        FROM scored
                        ORDER BY hybrid_score DESC, document_id, chunk_index
                        LIMIT :limit
                        """.formatted(vectorType, vectorType);
        return jdbcClient.sql(sql)
                .param("embedding", embedding).param("query", query).param("provider", provider)
                .param("dimensions", dimensions).param("tenantId", tenantId)
                .param("limit", limit).query(this::mapSearchResult).list();
    }

    public List<PublishedChunk> findCurrentPublishedChunks(UUID tenantId) {
        return jdbcClient.sql("""
                        SELECT c.id, c.content
                        FROM knowledge_chunk c
                        JOIN knowledge_document d ON d.id = c.document_id
                        WHERE c.tenant_id = :tenantId AND d.tenant_id = :tenantId
                          AND d.status = 'PUBLISHED'
                          AND c.document_version = d.published_version
                        ORDER BY c.document_id, c.chunk_index
                        """)
                .param("tenantId", tenantId)
                .query((rs, row) -> new PublishedChunk(
                        rs.getObject("id", UUID.class), rs.getString("content")))
                .list();
    }

    public boolean isPublishedChunk(UUID tenantId, UUID documentId, UUID chunkId) {
        return jdbcClient.sql("""
                        SELECT EXISTS(
                            SELECT 1 FROM knowledge_chunk c
                            JOIN knowledge_document d ON d.id = c.document_id
                            WHERE c.tenant_id = :tenantId AND d.tenant_id = :tenantId
                              AND d.id = :documentId AND c.id = :chunkId
                              AND d.status = 'PUBLISHED'
                              AND c.document_version = d.published_version
                        )
                        """)
                .param("tenantId", tenantId).param("documentId", documentId).param("chunkId", chunkId)
                .query(Boolean.class).single();
    }

    public boolean isPublishedDocument(UUID tenantId, UUID documentId) {
        return jdbcClient.sql("""
                        SELECT EXISTS(
                            SELECT 1 FROM knowledge_document
                            WHERE tenant_id = :tenantId AND id = :documentId AND status = 'PUBLISHED'
                        )
                        """)
                .param("tenantId", tenantId).param("documentId", documentId)
                .query(Boolean.class).single();
    }

    public boolean citationIsIntact(UUID tenantId, KnowledgeSearchResult result) {
        return jdbcClient.sql("""
                        SELECT EXISTS(
                            SELECT 1 FROM knowledge_chunk c
                            JOIN knowledge_document d ON d.id = c.document_id
                            WHERE c.tenant_id = :tenantId AND d.tenant_id = :tenantId
                              AND d.id = :documentId AND c.id = :chunkId
                              AND d.title = :title AND d.document_type = :type
                              AND c.document_version = :version AND c.chunk_index = :chunkIndex
                              AND c.content = :quote AND d.status = 'PUBLISHED'
                              AND c.document_version = d.published_version
                        )
                        """)
                .param("tenantId", tenantId).param("documentId", result.documentId())
                .param("chunkId", result.chunkId()).param("title", result.documentTitle())
                .param("type", result.documentType()).param("version", result.documentVersion())
                .param("chunkIndex", result.chunkIndex()).param("quote", result.quote())
                .query(Boolean.class).single();
    }

    public RagEvaluationCase insertEvaluationCase(
            UUID id, UUID tenantId, String name, String query,
            UUID expectedDocumentId, UUID expectedChunkId, UUID createdBy) {
        jdbcClient.sql("""
                        INSERT INTO rag_evaluation_case(
                            id, tenant_id, name, query, expected_document_id, expected_chunk_id, created_by
                        ) VALUES (
                            :id, :tenantId, :name, :query, :expectedDocumentId, :expectedChunkId, :createdBy
                        )
                        """)
                .param("id", id).param("tenantId", tenantId).param("name", name).param("query", query)
                .param("expectedDocumentId", expectedDocumentId).param("expectedChunkId", expectedChunkId)
                .param("createdBy", createdBy).update();
        return findEvaluationCase(tenantId, id).orElseThrow();
    }

    public List<RagEvaluationCase> findEvaluationCases(UUID tenantId) {
        return jdbcClient.sql("""
                        SELECT id, tenant_id, name, query, expected_document_id, expected_chunk_id,
                               created_by, created_at
                        FROM rag_evaluation_case WHERE tenant_id = :tenantId
                        ORDER BY created_at, id
                        """)
                .param("tenantId", tenantId).query(this::mapEvaluationCase).list();
    }

    public Optional<RagEvaluationCase> findEvaluationCase(UUID tenantId, UUID id) {
        return jdbcClient.sql("""
                        SELECT id, tenant_id, name, query, expected_document_id, expected_chunk_id,
                               created_by, created_at
                        FROM rag_evaluation_case WHERE tenant_id = :tenantId AND id = :id
                        """)
                .param("tenantId", tenantId).param("id", id).query(this::mapEvaluationCase).optional();
    }

    public RagEvaluationRun insertEvaluationRun(
            UUID id, UUID tenantId, int topK, String provider, List<RagEvaluationCaseResult> results,
            double recallAtK, double mrr, boolean tenantIsolation, boolean draftExclusion,
            boolean citationIntegrity, UUID createdBy) {
        jdbcClient.sql("""
                        INSERT INTO rag_evaluation_run(
                            id, tenant_id, top_k, embedding_provider, case_count, recall_at_k, mrr,
                            tenant_isolation_passed, draft_exclusion_passed, citation_integrity_passed, created_by
                        ) VALUES (
                            :id, :tenantId, :topK, :provider, :caseCount, :recallAtK, :mrr,
                            :tenantIsolation, :draftExclusion, :citationIntegrity, :createdBy
                        )
                        """)
                .param("id", id).param("tenantId", tenantId).param("topK", topK).param("provider", provider)
                .param("caseCount", results.size()).param("recallAtK", recallAtK).param("mrr", mrr)
                .param("tenantIsolation", tenantIsolation).param("draftExclusion", draftExclusion)
                .param("citationIntegrity", citationIntegrity).param("createdBy", createdBy).update();
        for (RagEvaluationCaseResult result : results) {
            jdbcClient.sql("""
                            INSERT INTO rag_evaluation_result(
                                id, tenant_id, run_id, evaluation_case_id, first_relevant_rank,
                                recalled, reciprocal_rank, returned_count
                            ) VALUES (
                                :id, :tenantId, :runId, :caseId, :rank,
                                :recalled, :reciprocalRank, :returnedCount
                            )
                            """)
                    .param("id", UUID.randomUUID()).param("tenantId", tenantId).param("runId", id)
                    .param("caseId", result.evaluationCaseId()).param("rank", result.firstRelevantRank())
                    .param("recalled", result.recalled()).param("reciprocalRank", result.reciprocalRank())
                    .param("returnedCount", result.results().size()).update();
        }
        return new RagEvaluationRun(id, topK, provider, results.size(), recallAtK, mrr,
                tenantIsolation, draftExclusion, citationIntegrity, results, java.time.Instant.now());
    }

    private RagEvaluationCase mapEvaluationCase(ResultSet rs, int row) throws SQLException {
        return new RagEvaluationCase(
                rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class), rs.getString("name"),
                rs.getString("query"), rs.getObject("expected_document_id", UUID.class),
                rs.getObject("expected_chunk_id", UUID.class), rs.getObject("created_by", UUID.class),
                rs.getTimestamp("created_at").toInstant());
    }

    private KnowledgeDocument mapDocument(ResultSet rs, int row) throws SQLException {
        Timestamp publishedAt = rs.getTimestamp("published_at");
        return new KnowledgeDocument(
                rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class), rs.getString("title"),
                rs.getString("document_type"), rs.getString("content"), rs.getString("status"),
                rs.getInt("published_version"), rs.getString("source_scope"), rs.getInt("source_priority"),
                rs.getString("source_provider"), rs.getString("source_identifier"),
                rs.getString("source_url"), rs.getString("source_venue"),
                (Integer) rs.getObject("source_year"), rs.getObject("created_by", UUID.class),
                rs.getObject("updated_by", UUID.class), rs.getLong("version"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
                publishedAt == null ? null : publishedAt.toInstant());
    }

    private KnowledgeSearchResult mapSearchResult(ResultSet rs, int row) throws SQLException {
        return new KnowledgeSearchResult(
                rs.getObject("document_id", UUID.class), rs.getString("title"), rs.getString("document_type"),
                rs.getInt("document_version"), rs.getObject("chunk_id", UUID.class), rs.getInt("chunk_index"),
                rs.getString("content"), rs.getDouble("semantic_score"), rs.getDouble("keyword_score"),
                rs.getDouble("hybrid_score"));
    }

    public record PublishedChunk(UUID chunkId, String content) {}
}
