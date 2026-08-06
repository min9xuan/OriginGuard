package com.originguard.agent.application;

import com.originguard.identity.domain.CurrentActor;
import com.originguard.investigation.domain.InvestigationCase;
import com.originguard.investigation.infrastructure.InvestigationCaseRepository;
import com.originguard.investigation.infrastructure.InvestigationWorkflowRepository;
import org.springframework.stereotype.Component;

@Component
public class AgentContextBuilder {
    private final InvestigationCaseRepository caseRepository;
    private final InvestigationWorkflowRepository workflowRepository;

    public AgentContextBuilder(
            InvestigationCaseRepository caseRepository,
            InvestigationWorkflowRepository workflowRepository) {
        this.caseRepository = caseRepository;
        this.workflowRepository = workflowRepository;
    }

    public AgentExecutionContext build(InvestigationCase investigationCase, CurrentActor actor) {
        return new AgentExecutionContext(
                actor,
                investigationCase,
                caseRepository.findAssets(investigationCase.tenantId(), investigationCase.id()),
                workflowRepository.countEvidence(investigationCase.tenantId(), investigationCase.id()));
    }
}
