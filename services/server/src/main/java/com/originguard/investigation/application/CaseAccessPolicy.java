package com.originguard.investigation.application;

import com.originguard.identity.domain.CurrentActor;
import com.originguard.investigation.domain.InvestigationCase;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

@Component
public class CaseAccessPolicy {
    public void requireCanModify(InvestigationCase investigationCase, CurrentActor actor) {
        if (!actor.userId().equals(investigationCase.assignedInvestigatorId())) {
            throw new AccessDeniedException("Only the assigned investigator can modify the case");
        }
    }

    public void requireAssignedInvestigator(InvestigationCase investigationCase, CurrentActor actor) {
        if (!actor.userId().equals(investigationCase.assignedInvestigatorId())) {
            throw new AccessDeniedException("Only the assigned investigator can add evidence");
        }
    }
}
