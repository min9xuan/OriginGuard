package com.originguard.agent.domain;

import java.util.UUID;

public record AgentKnowledgeCitation(
        UUID id,
        UUID documentId,
        UUID chunkId,
        String documentTitle,
        String documentType,
        int documentVersion,
        int chunkIndex,
        String quote,
        double semanticScore,
        double keywordScore,
        double hybridScore,
        int citationOrder) {}
