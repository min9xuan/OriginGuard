package com.originguard.knowledge.interfaces;

import com.originguard.knowledge.application.KnowledgeDocumentService;
import com.originguard.knowledge.domain.KnowledgeDocument;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/knowledge-documents")
public class KnowledgeDocumentController {
    private final KnowledgeDocumentService service;

    public KnowledgeDocumentController(KnowledgeDocumentService service) { this.service = service; }

    @PostMapping
    @PreAuthorize("hasAuthority('knowledge:upload')")
    public ResponseEntity<KnowledgeDocument> create(@Valid @RequestBody DocumentRequest request) {
        KnowledgeDocument created = service.create(request.title(), request.documentType(), request.content());
        return ResponseEntity.created(URI.create("/api/v1/knowledge-documents/" + created.id())).body(created);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('knowledge:read')")
    public List<KnowledgeDocument> list() { return service.list(); }

    @GetMapping("/{documentId}")
    @PreAuthorize("hasAuthority('knowledge:read')")
    public KnowledgeDocument get(@PathVariable UUID documentId) { return service.get(documentId); }

    @PatchMapping("/{documentId}")
    @PreAuthorize("hasAuthority('knowledge:upload')")
    public KnowledgeDocument update(@PathVariable UUID documentId, @Valid @RequestBody DocumentRequest request) {
        return service.update(documentId, request.version(), request.title(), request.documentType(), request.content());
    }

    @PostMapping("/{documentId}/publish")
    @PreAuthorize("hasAuthority('knowledge:publish')")
    public KnowledgeDocumentService.PublishResult publish(
            @PathVariable UUID documentId, @Valid @RequestBody VersionRequest request) {
        return service.publish(documentId, request.version());
    }

    @PostMapping("/reindex")
    @PreAuthorize("hasAuthority('knowledge:publish')")
    public KnowledgeDocumentService.ReindexResult reindex() {
        return service.reindexPublished();
    }

    public record DocumentRequest(
            @NotBlank @Size(max = 200) String title,
            @NotBlank @Size(max = 32) String documentType,
            @NotBlank @Size(max = 100000) String content,
            @Min(0) long version) {}
    public record VersionRequest(@Min(0) long version) {}
}
