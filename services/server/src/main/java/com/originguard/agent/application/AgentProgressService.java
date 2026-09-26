package com.originguard.agent.application;

import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
public class AgentProgressService {
    private final Map<UUID, CopyOnWriteArrayList<SseEmitter>> subscribers = new ConcurrentHashMap<>();
    private final Map<UUID, Object> taskLocks = new ConcurrentHashMap<>();
    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public AgentProgressService(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    public SseEmitter subscribe(UUID taskId, long lastEventId) {
        SseEmitter emitter = new SseEmitter(30L * 60L * 1000L);
        synchronized (taskLocks.computeIfAbsent(taskId, ignored -> new Object())) {
            subscribers.computeIfAbsent(taskId, ignored -> new CopyOnWriteArrayList<>()).add(emitter);
            for (ProgressEvent event : eventsAfter(taskId, lastEventId)) {
                if (!send(emitter, event.id(), "progress", event.payload())) break;
            }
        }
        emitter.onCompletion(() -> remove(taskId, emitter));
        emitter.onTimeout(() -> remove(taskId, emitter));
        emitter.onError(error -> remove(taskId, emitter));
        send(emitter, null, "connected", Map.of(
                "taskId", taskId.toString(), "at", Instant.now().toString(),
                "lastEventId", Math.max(0, lastEventId)));
        return emitter;
    }

    public void publish(UUID taskId, String type, Map<String, ?> data) {
        Map<String, Object> payload = Map.of(
                "taskId", taskId.toString(), "type", type,
                "at", Instant.now().toString(), "data", data == null ? Map.of() : data);
        synchronized (taskLocks.computeIfAbsent(taskId, ignored -> new Object())) {
            long eventId = jdbcClient.sql("""
                            INSERT INTO agent_progress_event(task_id, event_type, payload)
                            VALUES (:taskId, :type, CAST(:payload AS jsonb)) RETURNING id
                            """)
                    .param("taskId", taskId).param("type", type).param("payload", json(payload))
                    .query(Long.class).single();
            jdbcClient.sql("""
                            DELETE FROM agent_progress_event
                            WHERE task_id = :taskId AND id < (
                                SELECT id FROM agent_progress_event WHERE task_id = :taskId
                                ORDER BY id DESC OFFSET 1999 LIMIT 1
                            )
                            """).param("taskId", taskId).update();
            var emitters = subscribers.get(taskId);
            if (emitters == null) return;
            for (SseEmitter emitter : emitters) {
                if (!send(emitter, eventId, "progress", payload)) remove(taskId, emitter);
            }
        }
    }

    private List<ProgressEvent> eventsAfter(UUID taskId, long lastEventId) {
        return jdbcClient.sql("""
                        SELECT id, payload::text AS payload
                        FROM agent_progress_event
                        WHERE task_id = :taskId AND id > :lastEventId
                        ORDER BY id
                        """)
                .param("taskId", taskId).param("lastEventId", Math.max(0, lastEventId))
                .query(this::mapEvent).list();
    }

    private ProgressEvent mapEvent(ResultSet rs, int row) throws SQLException {
        try {
            Map<String, Object> payload = objectMapper.readValue(
                    rs.getString("payload"), new TypeReference<Map<String, Object>>() {});
            return new ProgressEvent(rs.getLong("id"), Map.copyOf(new LinkedHashMap<>(payload)));
        } catch (RuntimeException exception) {
            throw new SQLException("Failed to read Agent progress event", exception);
        }
    }

    private String json(Object value) {
        return objectMapper.writeValueAsString(value);
    }

    private boolean send(SseEmitter emitter, Long id, String event, Object data) {
        try {
            SseEmitter.SseEventBuilder builder = SseEmitter.event().name(event).data(data);
            if (id != null) builder.id(Long.toString(id));
            emitter.send(builder);
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

    private record ProgressEvent(long id, Map<String, Object> payload) {}
}
