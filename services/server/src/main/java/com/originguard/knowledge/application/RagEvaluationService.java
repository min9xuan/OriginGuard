package com.originguard.knowledge.application;

import com.originguard.audit.application.AuditService;
import com.originguard.identity.application.CurrentActorProvider;
import com.originguard.identity.domain.CurrentActor;
import com.originguard.knowledge.domain.KnowledgeSearchResult;
import com.originguard.knowledge.domain.RagEvaluationCase;
import com.originguard.knowledge.domain.RagEvaluationCaseResult;
import com.originguard.knowledge.domain.RagEvaluationRun;
import com.originguard.knowledge.infrastructure.KnowledgeRepository;
import com.originguard.shared.application.BusinessConflictException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RagEvaluationService {
    private final KnowledgeRetriever retriever;
    private final KnowledgeRepository repository;
    private final CurrentActorProvider actorProvider;
    private final AuditService auditService;

    public RagEvaluationService(KnowledgeRetriever retriever, KnowledgeRepository repository,
                                CurrentActorProvider actorProvider, AuditService auditService) {
        this.retriever = retriever;
        this.repository = repository;
        this.actorProvider = actorProvider;
        this.auditService = auditService;
    }

    public DebugSearchResult debugSearch(String query, int topK) {
        CurrentActor actor = actorProvider.getRequiredActor();
        List<KnowledgeSearchResult> results = retriever.search(actor.tenantId(), query, topK);
        return new DebugSearchResult(query.trim(), topK, retriever.embeddingProviderCode(), results);
    }

    @Transactional
    public RagEvaluationCase createCase(
            String name, String query, UUID expectedDocumentId, UUID expectedChunkId) {
        CurrentActor actor = actorProvider.getRequiredActor();
        if (!repository.isPublishedDocument(actor.tenantId(), expectedDocumentId)) {
            throw new BusinessConflictException(
                    "RAG_EXPECTED_DOCUMENT_NOT_PUBLISHED",
                    "Expected document must be published in the current tenant");
        }
        if (expectedChunkId != null
                && !repository.isPublishedChunk(actor.tenantId(), expectedDocumentId, expectedChunkId)) {
            throw new BusinessConflictException(
                    "RAG_EXPECTED_CHUNK_INVALID",
                    "Expected chunk must belong to the current published document version");
        }
        RagEvaluationCase created = repository.insertEvaluationCase(
                UUID.randomUUID(), actor.tenantId(), name.trim(), query.trim(),
                expectedDocumentId, expectedChunkId, actor.userId());
        auditService.record(actor.tenantId(), actor.userId(), "RAG_EVALUATION_CASE_CREATED",
                "RAG_EVALUATION_CASE", created.id(), java.util.Map.of("name", created.name()));
        return created;
    }

    public List<RagEvaluationCase> listCases() {
        CurrentActor actor = actorProvider.getRequiredActor();
        return repository.findEvaluationCases(actor.tenantId());
    }

    @Transactional
    public RagEvaluationRun run(int topK) {
        CurrentActor actor = actorProvider.getRequiredActor();
        List<RagEvaluationCase> cases = repository.findEvaluationCases(actor.tenantId());
        if (cases.isEmpty()) {
            throw new BusinessConflictException(
                    "RAG_EVALUATION_CASES_REQUIRED", "Create at least one RAG evaluation case first");
        }
        List<RagEvaluationCaseResult> results = new ArrayList<>();
        boolean tenantIsolation = true;
        boolean draftExclusion = true;
        boolean citationIntegrity = true;
        for (RagEvaluationCase evaluationCase : cases) {
            List<KnowledgeSearchResult> retrieved = retriever.search(actor.tenantId(), evaluationCase.query(), topK);
            Integer firstRank = null;
            for (int index = 0; index < retrieved.size(); index++) {
                KnowledgeSearchResult result = retrieved.get(index);
                boolean relevant = evaluationCase.expectedChunkId() == null
                        ? result.documentId().equals(evaluationCase.expectedDocumentId())
                        : result.chunkId().equals(evaluationCase.expectedChunkId());
                if (relevant && firstRank == null) firstRank = index + 1;
                boolean publishedChunk = repository.isPublishedChunk(
                        actor.tenantId(), result.documentId(), result.chunkId());
                tenantIsolation &= publishedChunk;
                draftExclusion &= repository.isPublishedDocument(actor.tenantId(), result.documentId());
                citationIntegrity &= repository.citationIsIntact(actor.tenantId(), result);
            }
            results.add(new RagEvaluationCaseResult(
                    evaluationCase.id(), evaluationCase.name(), evaluationCase.query(),
                    evaluationCase.expectedDocumentId(), evaluationCase.expectedChunkId(), firstRank,
                    firstRank != null, firstRank == null ? 0.0 : 1.0 / firstRank, retrieved));
        }
        double recallAtK = results.stream().filter(RagEvaluationCaseResult::recalled).count() / (double) results.size();
        double mrr = results.stream().mapToDouble(RagEvaluationCaseResult::reciprocalRank).average().orElse(0.0);
        UUID runId = UUID.randomUUID();
        RagEvaluationRun run = repository.insertEvaluationRun(
                runId, actor.tenantId(), topK, retriever.embeddingProviderCode(), results,
                recallAtK, mrr, tenantIsolation, draftExclusion, citationIntegrity, actor.userId());
        auditService.record(actor.tenantId(), actor.userId(), "RAG_EVALUATION_RUN_COMPLETED",
                "RAG_EVALUATION_RUN", runId,
                java.util.Map.of("topK", topK, "caseCount", cases.size(), "recallAtK", recallAtK, "mrr", mrr));
        return run;
    }

    public record DebugSearchResult(
            String query, int topK, String embeddingProvider, List<KnowledgeSearchResult> results) {
        public DebugSearchResult { results = List.copyOf(results); }
    }
}
