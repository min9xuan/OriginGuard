package com.originguard.knowledge.domain;

import java.util.List;
import java.util.UUID;

public record RagEvaluationCaseResult(
        UUID evaluationCaseId,
        String name,
        String query,
        UUID expectedDocumentId,
        UUID expectedChunkId,
        Integer firstRelevantRank,
        boolean recalled,
        double reciprocalRank,
        List<KnowledgeSearchResult> results) {
    public RagEvaluationCaseResult { results = List.copyOf(results); }
}
