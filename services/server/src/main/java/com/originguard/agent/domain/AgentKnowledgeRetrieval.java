package com.originguard.agent.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AgentKnowledgeRetrieval(
        UUID id,
        UUID taskId,
        UUID caseId,
        String skillCode,
        String toolCode,
        String query,
        String retrievalMode,
        String embeddingProvider,
        boolean knowledgeAvailable,
        List<AgentKnowledgeCitation> citations,
        Instant createdAt) {
    public AgentKnowledgeRetrieval {
        citations = List.copyOf(citations);
    }
}
