package com.originguard.investigation.interfaces;

import com.originguard.audit.domain.AuditEntry;
import com.originguard.investigation.application.InvestigationCaseService;
import com.originguard.investigation.domain.CasePriority;
import com.originguard.investigation.domain.CaseStatus;
import com.originguard.investigation.domain.InvestigationCase;
import com.originguard.media.domain.MediaAsset;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
@RequestMapping("/api/v1/cases")
public class InvestigationCaseController {
    private final InvestigationCaseService service;

    public InvestigationCaseController(InvestigationCaseService service) {
        this.service = service;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('case:create')")
    public ResponseEntity<CaseDetailsView> create(@Valid @RequestBody CreateCaseRequest request) {
        var created = service.create(
                request.title(), request.description(), request.priority(), request.assetIds());
        return ResponseEntity.created(URI.create("/api/v1/cases/" + created.investigationCase().id()))
                .body(CaseDetailsView.from(created));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('case:read')")
    public List<CaseSummaryView> list() {
        return service.list().stream().map(CaseSummaryView::from).toList();
    }

    @GetMapping("/{caseId}")
    @PreAuthorize("hasAuthority('case:read')")
    public CaseDetailsView get(@PathVariable UUID caseId) {
        return CaseDetailsView.from(service.get(caseId));
    }

    @PatchMapping("/{caseId}")
    @PreAuthorize("hasAuthority('case:update')")
    public CaseDetailsView update(
            @PathVariable UUID caseId, @Valid @RequestBody UpdateCaseRequest request) {
        return CaseDetailsView.from(service.update(
                caseId,
                request.version(),
                request.title(),
                request.description(),
                request.priority()));
    }

    @PostMapping("/{caseId}/assets")
    @PreAuthorize("hasAuthority('case:update')")
    public CaseDetailsView linkAsset(
            @PathVariable UUID caseId, @Valid @RequestBody LinkAssetRequest request) {
        return CaseDetailsView.from(service.linkAsset(caseId, request.assetId(), request.version()));
    }

    @PostMapping("/{caseId}/transitions")
    @PreAuthorize("hasAnyAuthority('case:update', 'case:submit')")
    public CaseDetailsView transition(
            @PathVariable UUID caseId, @Valid @RequestBody TransitionCaseRequest request) {
        return CaseDetailsView.from(service.transition(caseId, request.version(), request.targetStatus()));
    }

    @GetMapping("/{caseId}/audit")
    @PreAuthorize("hasAuthority('audit:case:read')")
    public List<AuditEntryView> history(@PathVariable UUID caseId) {
        return service.history(caseId).stream().map(AuditEntryView::from).toList();
    }

    public record CreateCaseRequest(
            @NotBlank @Size(max = 200) String title,
            @Size(max = 2000) String description,
            @NotNull CasePriority priority,
            @NotNull @Size(max = 20) List<UUID> assetIds) {}

    public record UpdateCaseRequest(
            @NotBlank @Size(max = 200) String title,
            @Size(max = 2000) String description,
            @NotNull CasePriority priority,
            @Min(0) long version) {}

    public record LinkAssetRequest(@NotNull UUID assetId, @Min(0) long version) {}

    public record TransitionCaseRequest(@NotNull CaseStatus targetStatus, @Min(0) long version) {}

    public record CaseSummaryView(
            UUID id,
            UUID tenantId,
            String caseNumber,
            String title,
            String description,
            CasePriority priority,
            CaseStatus status,
            UUID createdBy,
            UUID assignedInvestigatorId,
            UUID assignedReviewerId,
            long version,
            Instant createdAt,
            Instant updatedAt) {
        static CaseSummaryView from(InvestigationCase investigationCase) {
            return new CaseSummaryView(
                    investigationCase.id(),
                    investigationCase.tenantId(),
                    investigationCase.caseNumber(),
                    investigationCase.title(),
                    investigationCase.description(),
                    investigationCase.priority(),
                    investigationCase.status(),
                    investigationCase.createdBy(),
                    investigationCase.assignedInvestigatorId(),
                    investigationCase.assignedReviewerId(),
                    investigationCase.version(),
                    investigationCase.createdAt(),
                    investigationCase.updatedAt());
        }
    }

    public record CaseDetailsView(CaseSummaryView investigationCase, List<MediaAssetView> assets) {
        static CaseDetailsView from(InvestigationCaseService.CaseDetails details) {
            return new CaseDetailsView(
                    CaseSummaryView.from(details.investigationCase()),
                    details.assets().stream().map(MediaAssetView::from).toList());
        }
    }

    public record MediaAssetView(
            UUID id,
            String originalFilename,
            String contentType,
            long byteSize,
            String sha256,
            String storageStatus,
            Instant createdAt) {
        static MediaAssetView from(MediaAsset asset) {
            return new MediaAssetView(
                    asset.id(),
                    asset.originalFilename(),
                    asset.contentType(),
                    asset.byteSize(),
                    asset.sha256(),
                    asset.storageStatus(),
                    asset.createdAt());
        }
    }

    public record AuditEntryView(
            UUID id,
            UUID actorUserId,
            String action,
            Map<String, Object> details,
            Instant createdAt) {
        static AuditEntryView from(AuditEntry entry) {
            return new AuditEntryView(
                    entry.id(), entry.actorUserId(), entry.action(), entry.details(), entry.createdAt());
        }
    }
}
