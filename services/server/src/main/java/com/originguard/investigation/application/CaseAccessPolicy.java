package com.originguard.investigation.application;

import com.originguard.identity.domain.CurrentActor;
import com.originguard.investigation.domain.InvestigationCase;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

@Component
public class CaseAccessPolicy {
    public void requireCanModify(InvestigationCase investigationCase, CurrentActor actor) {
        boolean ownsCase = investigationCase.createdBy().equals(actor.userId());
        boolean assigned = actor.userId().equals(investigationCase.assignedInvestigatorId());
        if (!ownsCase && !assigned) {
            throw new AccessDeniedException("Only the case creator or assigned investigator can modify it");
        }
    }
}
