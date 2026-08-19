package com.originguard.knowledge.interfaces;

import com.originguard.knowledge.application.RagKnowledgeExpansionService;
import com.originguard.knowledge.domain.ExternalKnowledgeCandidate;
import com.originguard.knowledge.domain.KnowledgeDocument;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/rag/knowledge-expansion")
public class RagKnowledgeExpansionController {
    private final RagKnowledgeExpansionService service;

    public RagKnowledgeExpansionController(RagKnowledgeExpansionService service) {
        this.service = service;
    }

    @GetMapping("/venues")
    @PreAuthorize("hasAuthority('knowledge:upload')")
    public List<Map<String, String>> venues() {
        return service.supportedVenues();
    }

    @PostMapping("/search")
    @PreAuthorize("hasAuthority('knowledge:upload')")
    public RagKnowledgeExpansionService.DiscoveryResult search(@Valid @RequestBody SearchRequest request) {
        return service.discover(
                request.query(), request.venueCodes(), request.fromYear(), request.toYear(), request.limit());
    }

    @PostMapping("/drafts")
    @PreAuthorize("hasAuthority('knowledge:upload')")
    public KnowledgeDocument createDraft(@Valid @RequestBody CandidateRequest request) {
        return service.createDraft(request.toCandidate());
    }

    public record SearchRequest(
            @NotBlank @Size(max = 300) String query,
            @NotEmpty @Size(max = 6) List<@NotBlank @Size(max = 32) String> venueCodes,
            @Min(2000) @Max(2100) int fromYear,
            @Min(2000) @Max(2100) int toYear,
            @Min(1) @Max(30) int limit) {}

    public record CandidateRequest(
            @NotBlank @Size(max = 32) String sourceProvider,
            @NotBlank @Size(max = 200) String sourceIdentifier,
            @NotBlank @Size(max = 200) String title,
            @NotBlank @Size(max = 30000) String abstractText,
            @Size(max = 20) List<@NotBlank @Size(max = 200) String> authors,
            @NotBlank @Size(max = 32) String venueCode,
            @NotBlank @Size(max = 200) String venueName,
            @Min(1900) @Max(2100) int publicationYear,
            @Size(max = 200) String doi,
            @NotBlank @Size(max = 2000) String sourceUrl,
            @Min(0) int citedByCount) {
        ExternalKnowledgeCandidate toCandidate() {
            return new ExternalKnowledgeCandidate(
                    sourceProvider, sourceIdentifier, title, abstractText,
                    authors == null ? List.of() : List.copyOf(authors), venueCode, venueName,
                    publicationYear, doi == null ? "" : doi, sourceUrl, citedByCount);
        }
    }
}
