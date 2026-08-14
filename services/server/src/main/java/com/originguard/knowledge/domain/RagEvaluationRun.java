package com.originguard.knowledge.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RagEvaluationRun(
        UUID id,
        int topK,
        String embeddingProvider,
        int caseCount,
        double recallAtK,
        double mrr,
        boolean tenantIsolationPassed,
        boolean draftExclusionPassed,
        boolean citationIntegrityPassed,
        List<RagEvaluationCaseResult> caseResults,
        Instant createdAt) {
    public RagEvaluationRun { caseResults = List.copyOf(caseResults); }
}
