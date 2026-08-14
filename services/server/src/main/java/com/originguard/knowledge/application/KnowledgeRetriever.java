package com.originguard.knowledge.application;

import com.originguard.knowledge.domain.KnowledgeSearchResult;
import com.originguard.knowledge.infrastructure.KnowledgeRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeRetriever {
    private final KnowledgeRepository repository;
    private final EmbeddingProvider embeddingProvider;

    public KnowledgeRetriever(KnowledgeRepository repository, EmbeddingProvider embeddingProvider) {
        this.repository = repository;
        this.embeddingProvider = embeddingProvider;
    }

    public List<KnowledgeSearchResult> search(UUID tenantId, String query, int limit) {
        String normalized = query == null || query.isBlank() ? "media forensic guidance" : query.trim();
        return repository.search(
                tenantId, normalized, embeddingProvider.code(), embeddingProvider.dimensions(),
                embeddingProvider.embedAsVector(normalized), limit);
    }

    public String embeddingProviderCode() { return embeddingProvider.code(); }
}
