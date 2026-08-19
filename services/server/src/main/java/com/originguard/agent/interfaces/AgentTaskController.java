package com.originguard.agent.interfaces;

import com.originguard.agent.application.AgentTaskService;
import com.originguard.agent.domain.AgentTask;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/agent-tasks")
public class AgentTaskController {
    private final AgentTaskService service;

    public AgentTaskController(AgentTaskService service) {
        this.service = service;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('agent:run')")
    public ResponseEntity<AgentTaskService.AgentTaskDetails> create(
            @Valid @RequestBody CreateAgentTaskRequest request) {
        var created = service.create(request.caseId(), request.goal(), request.stepBudget());
        return ResponseEntity.created(URI.create("/api/v1/agent-tasks/" + created.task().id()))
                .body(created);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('agent:trace:read')")
    public List<AgentTask> list() {
        return service.list();
    }

    @GetMapping("/{taskId}")
    @PreAuthorize("hasAuthority('agent:trace:read')")
    public AgentTaskService.AgentTaskDetails get(@PathVariable UUID taskId) {
        return service.get(taskId);
    }

    @GetMapping("/{taskId}/observations/{observationId}/artifacts/{artifactId}")
    @PreAuthorize("hasAuthority('agent:trace:read')")
    public ResponseEntity<byte[]> artifact(
            @PathVariable UUID taskId,
            @PathVariable UUID observationId,
            @PathVariable UUID artifactId) {
        var artifact = service.readObservationArtifact(taskId, observationId, artifactId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(artifact.contentType()))
                .contentLength(artifact.content().length)
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=300")
                .header("X-Content-Type-Options", "nosniff")
                .body(artifact.content());
    }

    @PostMapping("/{taskId}/run")
    @PreAuthorize("hasAuthority('agent:run')")
    public AgentTaskService.AgentTaskDetails run(
            @PathVariable UUID taskId, @Valid @RequestBody VersionRequest request) {
        return service.run(taskId, request.version());
    }

    @PostMapping("/{taskId}/cancel")
    @PreAuthorize("hasAuthority('agent:cancel')")
    public AgentTaskService.AgentTaskDetails cancel(
            @PathVariable UUID taskId, @Valid @RequestBody VersionRequest request) {
        return service.cancel(taskId, request.version());
    }

    public record CreateAgentTaskRequest(
            @NotNull UUID caseId,
            @NotBlank @Size(max = 500) String goal,
            @Min(9) @Max(16) int stepBudget) {}

    public record VersionRequest(@Min(0) long version) {}
}
