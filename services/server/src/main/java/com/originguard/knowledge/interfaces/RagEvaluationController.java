package com.originguard.knowledge.interfaces;

import com.originguard.knowledge.application.RagEvaluationService;
import com.originguard.knowledge.domain.RagEvaluationCase;
import com.originguard.knowledge.domain.RagEvaluationRun;
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
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/rag")
public class RagEvaluationController {
    private final RagEvaluationService service;

    public RagEvaluationController(RagEvaluationService service) { this.service = service; }

    @PostMapping("/debug-search")
    @PreAuthorize("hasAuthority('knowledge:read')")
    public RagEvaluationService.DebugSearchResult debugSearch(@Valid @RequestBody SearchRequest request) {
        return service.debugSearch(request.query(), request.topK());
    }

    @GetMapping("/evaluation-cases")
    @PreAuthorize("hasAuthority('knowledge:read')")
    public List<RagEvaluationCase> listCases() { return service.listCases(); }

    @PostMapping("/evaluation-cases")
    @PreAuthorize("hasAuthority('knowledge:upload')")
    public ResponseEntity<RagEvaluationCase> createCase(@Valid @RequestBody EvaluationCaseRequest request) {
        RagEvaluationCase created = service.createCase(
                request.name(), request.query(), request.expectedDocumentId(), request.expectedChunkId());
        return ResponseEntity.created(URI.create("/api/v1/rag/evaluation-cases/" + created.id())).body(created);
    }

    @PostMapping("/evaluation-runs")
    @PreAuthorize("hasAuthority('knowledge:upload')")
    public RagEvaluationRun run(@Valid @RequestBody RunRequest request) { return service.run(request.topK()); }

    public record SearchRequest(@NotBlank @Size(max = 1000) String query, @Min(1) @Max(20) int topK) {}
    public record EvaluationCaseRequest(
            @NotBlank @Size(max = 200) String name,
            @NotBlank @Size(max = 1000) String query,
            @NotNull UUID expectedDocumentId,
            UUID expectedChunkId) {}
    public record RunRequest(@Min(1) @Max(20) int topK) {}
}
