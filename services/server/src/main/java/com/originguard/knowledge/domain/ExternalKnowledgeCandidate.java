package com.originguard.knowledge.domain;

import java.util.List;

public record ExternalKnowledgeCandidate(
        String sourceProvider,
        String sourceIdentifier,
        String title,
        String abstractText,
        List<String> authors,
        String venueCode,
        String venueName,
        int publicationYear,
        String doi,
        String sourceUrl,
        int citedByCount) {}
