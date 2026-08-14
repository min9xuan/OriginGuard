package com.originguard.knowledge.application;

import com.originguard.audit.application.AuditService;
import com.originguard.identity.application.CurrentActorProvider;
import com.originguard.identity.domain.CurrentActor;
import com.originguard.knowledge.domain.KnowledgeDocument;
import com.originguard.knowledge.domain.KnowledgeSearchResult;
import com.originguard.knowledge.infrastructure.KnowledgeRepository;
import com.originguard.shared.application.BusinessConflictException;
import com.originguard.shared.application.ResourceNotFoundException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KnowledgeDocumentService {
    public static final String RESOURCE_TYPE = "KNOWLEDGE_DOCUMENT";
    private final KnowledgeRepository repository;
    private final CurrentActorProvider actorProvider;
    private final KnowledgeChunker chunker;
    private final EmbeddingProvider embeddingProvider;
    private final AuditService auditService;

    public KnowledgeDocumentService(KnowledgeRepository repository, CurrentActorProvider actorProvider,
                                    KnowledgeChunker chunker, EmbeddingProvider embeddingProvider,
                                    AuditService auditService) {
        this.repository = repository;
        this.actorProvider = actorProvider;
        this.chunker = chunker;
        this.embeddingProvider = embeddingProvider;
        this.auditService = auditService;
    }

    @Transactional
    public KnowledgeDocument create(String title, String type, String content) {
        CurrentActor actor = actorProvider.getRequiredActor();
        validateType(type);
        KnowledgeDocument created = repository.insert(
                UUID.randomUUID(), actor.tenantId(), title.trim(), type, content.trim(), actor.userId());
        auditService.record(actor.tenantId(), actor.userId(), "KNOWLEDGE_DOCUMENT_CREATED",
                RESOURCE_TYPE, created.id(), Map.of("documentType", type));
        return created;
    }

    public List<KnowledgeDocument> list() {
        CurrentActor actor = actorProvider.getRequiredActor();
        return repository.findAll(actor.tenantId(), actor.hasPermission("knowledge:upload"));
    }

    public KnowledgeDocument get(UUID id) {
        CurrentActor actor = actorProvider.getRequiredActor();
        KnowledgeDocument document = require(actor.tenantId(), id);
        if (document.status().equals("DRAFT") && !actor.hasPermission("knowledge:upload")) {
            throw new AccessDeniedException("Draft knowledge is only visible to knowledge maintainers");
        }
        return document;
    }

    @Transactional
    public KnowledgeDocument update(UUID id, long version, String title, String type, String content) {
        CurrentActor actor = actorProvider.getRequiredActor();
        validateType(type);
        require(actor.tenantId(), id);
        if (!repository.updateDraft(actor.tenantId(), id, version, title.trim(), type, content.trim(), actor.userId())) {
            throw new BusinessConflictException(
                    "KNOWLEDGE_VERSION_CONFLICT", "Only the current draft version can be edited");
        }
        KnowledgeDocument updated = require(actor.tenantId(), id);
        auditService.record(actor.tenantId(), actor.userId(), "KNOWLEDGE_DOCUMENT_UPDATED",
                RESOURCE_TYPE, id, Map.of("version", updated.version()));
        return updated;
    }

    @Transactional
    public PublishResult publish(UUID id, long version) {
        CurrentActor actor = actorProvider.getRequiredActor();
        KnowledgeDocument draft = require(actor.tenantId(), id);
        if (!draft.status().equals("DRAFT") || draft.version() != version) {
            throw new BusinessConflictException(
                    "KNOWLEDGE_VERSION_CONFLICT", "Only the current draft version can be published");
        }
        int publishedVersion = draft.publishedVersion() + 1;
        List<String> chunks = chunker.split(draft.content());
        if (chunks.isEmpty()) {
            throw new BusinessConflictException("KNOWLEDGE_CONTENT_EMPTY", "Knowledge content cannot be empty");
        }
        for (int index = 0; index < chunks.size(); index++) {
            String chunk = chunks.get(index);
            repository.insertChunk(UUID.randomUUID(), actor.tenantId(), id, publishedVersion,
                    index, chunk, embeddingProvider.code(), embeddingProvider.dimensions(),
                    embeddingProvider.embedAsVector(chunk));
        }
        if (!repository.markPublished(actor.tenantId(), id, version, actor.userId())) {
            throw new BusinessConflictException("KNOWLEDGE_VERSION_CONFLICT", "Knowledge document changed while publishing");
        }
        KnowledgeDocument published = require(actor.tenantId(), id);
        auditService.record(actor.tenantId(), actor.userId(), "KNOWLEDGE_DOCUMENT_PUBLISHED",
                RESOURCE_TYPE, id, Map.of("publishedVersion", publishedVersion, "chunkCount", chunks.size()));
        return new PublishResult(published, chunks.size(), embeddingProvider.code());
    }

    @Transactional
    public ReindexResult reindexPublished() {
        CurrentActor actor = actorProvider.getRequiredActor();
        List<KnowledgeRepository.PublishedChunk> chunks = repository.findCurrentPublishedChunks(actor.tenantId());
        for (KnowledgeRepository.PublishedChunk chunk : chunks) {
            repository.upsertEmbedding(
                    chunk.chunkId(), actor.tenantId(), embeddingProvider.code(), embeddingProvider.dimensions(),
                    embeddingProvider.embedAsVector(chunk.content()));
        }
        auditService.record(actor.tenantId(), actor.userId(), "KNOWLEDGE_EMBEDDINGS_REINDEXED",
                RESOURCE_TYPE, actor.tenantId(), Map.of(
                        "embeddingProvider", embeddingProvider.code(),
                        "dimensions", embeddingProvider.dimensions(),
                        "chunkCount", chunks.size()));
        return new ReindexResult(embeddingProvider.code(), embeddingProvider.dimensions(), chunks.size());
    }

    private KnowledgeDocument require(UUID tenantId, UUID id) {
        return repository.findById(tenantId, id).orElseThrow(() -> new ResourceNotFoundException(
                "KNOWLEDGE_DOCUMENT_NOT_FOUND", "Knowledge document was not found"));
    }

    private void validateType(String type) {
        if (!List.of("FORENSIC_GUIDE", "POLICY", "MODEL_CARD", "OTHER").contains(type)) {
            throw new IllegalArgumentException("Unsupported knowledge document type");
        }
    }

    public record PublishResult(KnowledgeDocument document, int chunkCount, String embeddingProvider) {}
    public record ReindexResult(String embeddingProvider, int dimensions, int chunkCount) {}
}
