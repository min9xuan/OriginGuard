package com.originguard.agent.application;

import com.originguard.agent.infrastructure.AgentTaskRepository;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Persists user-visible, auditable summaries while a compound Agent tool is still running. */
@Component
public class AgentExecutionEventRecorder {
    private final AgentTaskRepository repository;

    public AgentExecutionEventRecorder(AgentTaskRepository repository) {
        this.repository = repository;
    }

    public void recordAigc(
            AgentExecutionContext context,
            UUID taskId,
            String stepType,
            Map<String, ?> input,
            Map<String, ?> output) {
        repository.appendStep(
                context.actor().tenantId(),
                taskId,
                stepType,
                "SUCCEEDED",
                SkillRegistry.AIGC_DETECTION_SKILL,
                AigcDetectionTool.CODE,
                input,
                output);
    }
}
