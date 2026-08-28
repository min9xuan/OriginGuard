package com.originguard.agent.application;

import com.originguard.assistant.application.AssistantWorkbenchService;
import com.originguard.identity.application.CurrentActorProvider;
import com.originguard.identity.domain.CurrentActor;
import com.originguard.identity.domain.UserAccount;
import com.originguard.identity.infrastructure.IdentityRepository;
import com.originguard.shared.application.ResourceNotFoundException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(name = "originguard.agent.async.enabled", havingValue = "true")
public class AgentTaskConsumer {
    private final ObjectMapper objectMapper;
    private final IdentityRepository identities;
    private final CurrentActorProvider actors;
    private final AgentTaskService tasks;
    private final AssistantWorkbenchService assistant;
    private final AgentProgressService progress;

    public AgentTaskConsumer(ObjectMapper objectMapper, IdentityRepository identities,
            CurrentActorProvider actors, AgentTaskService tasks, AssistantWorkbenchService assistant,
            AgentProgressService progress) {
        this.objectMapper = objectMapper;
        this.identities = identities;
        this.actors = actors;
        this.tasks = tasks;
        this.assistant = assistant;
        this.progress = progress;
    }

    @RabbitListener(queues = "${originguard.agent.async.queue:originguard.agent.analysis}")
    public void consume(String payload) {
        AgentExecutionMessage message = objectMapper.readValue(payload, AgentExecutionMessage.class);
        UserAccount user = identities.findById(message.userId()).orElseThrow(() ->
                new ResourceNotFoundException("AGENT_USER_NOT_FOUND", "Queued Agent user was not found"));
        if (!user.enabled()) throw new IllegalStateException("Queued Agent user is disabled");
        CurrentActor actor = new CurrentActor(
                user.id(), user.tenantId(), user.tenantCode(), user.username(), user.displayName(),
                user.roles(), user.permissions());
        actors.runAs(actor, () -> {
            AgentTaskService.AgentTaskDetails current = tasks.get(message.taskId());
            AgentTaskService.AgentTaskDetails completed = switch (current.task().status()) {
                case PENDING -> tasks.run(message.taskId(), message.expectedVersion());
                case RUNNING -> current;
                default -> current;
            };
            if (message.conversationId() != null && completed.task().status() != com.originguard.agent.domain.AgentTaskStatus.RUNNING) {
                assistant.completeQueuedAgent(
                        message.conversationId(), message.question(), message.assetId(), completed);
                progress.publish(message.taskId(), "ASSISTANT_RESULT_READY", java.util.Map.of(
                        "conversationId", message.conversationId().toString(),
                        "status", completed.task().status().name()));
            }
            return completed;
        });
    }
}
