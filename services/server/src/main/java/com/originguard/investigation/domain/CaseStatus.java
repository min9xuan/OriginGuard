package com.originguard.investigation.domain;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public enum CaseStatus {
    DRAFT,
    READY,
    INVESTIGATING,
    WAITING_CONFIRMATION,
    COMPLETED,
    FAILED,
    ARCHIVED;

    private static final Map<CaseStatus, Set<CaseStatus>> TRANSITIONS = Map.of(
            DRAFT, EnumSet.of(READY),
            READY, EnumSet.of(INVESTIGATING),
            INVESTIGATING, EnumSet.of(WAITING_CONFIRMATION, FAILED),
            FAILED, EnumSet.of(INVESTIGATING),
            WAITING_CONFIRMATION, EnumSet.of(COMPLETED, INVESTIGATING),
            COMPLETED, EnumSet.of(ARCHIVED),
            ARCHIVED, EnumSet.noneOf(CaseStatus.class));

    public boolean canTransitionTo(CaseStatus target) {
        return TRANSITIONS.get(this).contains(target);
    }
}
