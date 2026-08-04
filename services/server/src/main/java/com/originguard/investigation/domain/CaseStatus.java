package com.originguard.investigation.domain;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public enum CaseStatus {
    DRAFT,
    READY,
    INVESTIGATING,
    WAITING_REVIEW,
    CONFIRMED,
    REJECTED,
    FAILED,
    ARCHIVED;

    private static final Map<CaseStatus, Set<CaseStatus>> TRANSITIONS = Map.of(
            DRAFT, EnumSet.of(READY),
            READY, EnumSet.of(INVESTIGATING),
            INVESTIGATING, EnumSet.of(WAITING_REVIEW, FAILED),
            FAILED, EnumSet.of(INVESTIGATING),
            WAITING_REVIEW, EnumSet.of(CONFIRMED, REJECTED),
            REJECTED, EnumSet.of(INVESTIGATING),
            CONFIRMED, EnumSet.of(ARCHIVED),
            ARCHIVED, EnumSet.noneOf(CaseStatus.class));

    public boolean canTransitionTo(CaseStatus target) {
        return TRANSITIONS.get(this).contains(target);
    }
}
