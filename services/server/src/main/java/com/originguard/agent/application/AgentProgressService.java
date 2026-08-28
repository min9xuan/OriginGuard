package com.originguard.agent.application;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class AgentProgressService {
    private final Map<UUID, CopyOnWriteArrayList<SseEmitter>> subscribers = new ConcurrentHashMap<>();

    public SseEmitter subscribe(UUID taskId) {
        SseEmitter emitter = new SseEmitter(30L * 60L * 1000L);
        subscribers.computeIfAbsent(taskId, ignored -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> remove(taskId, emitter));
        emitter.onTimeout(() -> remove(taskId, emitter));
        emitter.onError(error -> remove(taskId, emitter));
        send(emitter, "connected", Map.of("taskId", taskId.toString(), "at", Instant.now().toString()));
        return emitter;
    }

    public void publish(UUID taskId, String type, Map<String, ?> data) {
        var emitters = subscribers.get(taskId);
        if (emitters == null) return;
        Map<String, Object> payload = Map.of(
                "taskId", taskId.toString(), "type", type,
                "at", Instant.now().toString(), "data", data == null ? Map.of() : data);
        for (SseEmitter emitter : emitters) {
            if (!send(emitter, "progress", payload)) remove(taskId, emitter);
        }
    }

    private boolean send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
            return true;
        } catch (IOException | IllegalStateException exception) {
            return false;
        }
    }

    private void remove(UUID taskId, SseEmitter emitter) {
        var emitters = subscribers.get(taskId);
        if (emitters == null) return;
        emitters.remove(emitter);
        if (emitters.isEmpty()) subscribers.remove(taskId, emitters);
    }
}
