package com.originguard.knowledge.domain;

import java.util.UUID;

public record KnowledgeSearchResult(
        UUID documentId, String documentTitle, String documentType, int documentVersion,
        UUID chunkId, int chunkIndex, String quote,
        double semanticScore, double keywordScore, double hybridScore) {}
