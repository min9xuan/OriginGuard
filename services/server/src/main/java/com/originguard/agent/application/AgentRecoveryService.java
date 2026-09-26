package com.originguard.agent.application;

import com.originguard.agent.domain.AgentTaskStatus;
import com.originguard.agent.infrastructure.AgentTaskRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "originguard.agent.async.enabled", havingValue = "true")
public class AgentRecoveryService {
    private final AgentTaskRepository tasks;
    private final AgentTaskDispatcher dispatcher;
    private final Duration staleAfter;
    private final AtomicBoolean recoveryRunning = new AtomicBoolean();

    public AgentRecoveryService(AgentTaskRepository tasks, AgentTaskDispatcher dispatcher,
            @Value("${originguard.agent.reliability.stale-after:PT15M}") Duration staleAfter) {
        this.tasks = tasks;
        this.dispatcher = dispatcher;
        this.staleAfter = staleAfter;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        if (!recoveryRunning.compareAndSet(false, true)) return;
        try {
            Instant staleBefore = Instant.now().minus(staleAfter);
            for (var task : tasks.findRecoverable(staleBefore)) {
                if (task.status() == AgentTaskStatus.RUNNING
                        && !tasks.requeueStale(task.tenantId(), task.id(), staleBefore)) continue;
                var refreshed = tasks.findById(task.tenantId(), task.id()).orElse(task);
                dispatcher.recover(refreshed.id(), refreshed.createdBy(), refreshed.version());
            }
        } finally {
            recoveryRunning.set(false);
        }
    }

    @Scheduled(fixedDelayString = "${originguard.agent.reliability.recovery-scan-millis:60000}")
    public void scheduledRecovery() {
        recover();
    }
}
