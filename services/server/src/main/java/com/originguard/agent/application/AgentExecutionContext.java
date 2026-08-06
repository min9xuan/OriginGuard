package com.originguard.agent.application;

import com.originguard.identity.domain.CurrentActor;
import com.originguard.investigation.domain.InvestigationCase;
import com.originguard.media.domain.MediaAsset;
import java.util.List;

public record AgentExecutionContext(
        CurrentActor actor, InvestigationCase investigationCase, List<MediaAsset> assets, int humanEvidenceCount) {
    public AgentExecutionContext {
        assets = List.copyOf(assets);
    }
}
