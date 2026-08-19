package com.originguard.agent.application;

import com.originguard.identity.domain.CurrentActor;
import com.originguard.investigation.domain.InvestigationCase;
import com.originguard.media.domain.MediaAsset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record AgentExecutionContext(
        CurrentActor actor,
        InvestigationCase investigationCase,
        List<MediaAsset> assets,
        int humanEvidenceCount,
        Map<UUID, Map<String, Object>> mediaTypeContexts) {
    public AgentExecutionContext {
        assets = List.copyOf(assets);
        mediaTypeContexts = Map.copyOf(mediaTypeContexts);
    }

    public AgentExecutionContext(
            CurrentActor actor,
            InvestigationCase investigationCase,
            List<MediaAsset> assets,
            int humanEvidenceCount) {
        this(actor, investigationCase, assets, humanEvidenceCount, Map.of());
    }

    public AgentExecutionContext withMediaTypeContexts(
            Map<UUID, Map<String, Object>> contexts) {
        return new AgentExecutionContext(actor, investigationCase, assets, humanEvidenceCount, contexts);
    }
}
