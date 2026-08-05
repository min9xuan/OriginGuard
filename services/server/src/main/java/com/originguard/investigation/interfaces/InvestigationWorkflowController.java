package com.originguard.investigation.interfaces;

import com.originguard.investigation.application.InvestigationWorkflowService;
import com.originguard.investigation.domain.AssignableUser;
import com.originguard.investigation.domain.EvidenceConclusion;
import com.originguard.investigation.domain.EvidenceConfidence;
import com.originguard.investigation.domain.InvestigationCase;
import com.originguard.investigation.domain.InvestigationEvidence;
import com.originguard.investigation.domain.ReviewStatus;
import com.originguard.investigation.domain.ReviewTask;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/cases")
public class InvestigationWorkflowController {
    private final InvestigationWorkflowService service;

    public InvestigationWorkflowController(InvestigationWorkflowService service) {
        this.service = service;
    }

    @GetMapping("/assignees")
    @PreAuthorize("hasAuthority('case:read')")
    public List<AssignableUser> assignees() {
        return service.assignableUsers();
    }

    @PostMapping("/{caseId}/assignment")
    @PreAuthorize("hasAuthority('case:assign')")
    public InvestigationCase assign(
            @PathVariable UUID caseId, @Valid @RequestBody AssignmentRequest request) {
        return service.assign(
                caseId, request.version(), request.investigatorId(), request.reviewerId());
    }

    @GetMapping("/{caseId}/workflow")
    @PreAuthorize("hasAuthority('case:read')")
    public WorkflowView workflow(@PathVariable UUID caseId) {
        return WorkflowView.from(service.getWorkflow(caseId));
    }

    @PostMapping("/{caseId}/evidence")
    @PreAuthorize("hasAuthority('case:update')")
    public EvidenceView addEvidence(
            @PathVariable UUID caseId, @Valid @RequestBody AddEvidenceRequest request) {
        return EvidenceView.from(service.addEvidence(
                caseId,
                request.version(),
                request.assetId(),
                request.title(),
                request.observation(),
                request.conclusion(),
                request.confidence()));
    }

    @PostMapping("/{caseId}/reviews/{taskId}/decision")
    @PreAuthorize("hasAnyAuthority('review:approve', 'review:reject')")
    public WorkflowView decide(
            @PathVariable UUID caseId,
            @PathVariable UUID taskId,
            @Valid @RequestBody ReviewDecisionRequest request) {
        return WorkflowView.from(service.decide(
                caseId,
                taskId,
                request.taskVersion(),
                request.caseVersion(),
                request.decision(),
                request.reason()));
    }

    public record AssignmentRequest(
            @NotNull UUID investigatorId,
            @NotNull UUID reviewerId,
            @Min(0) long version) {}

    public record AddEvidenceRequest(
            @NotNull UUID assetId,
            @NotBlank @Size(max = 200) String title,
            @NotBlank @Size(max = 4000) String observation,
            @NotNull EvidenceConclusion conclusion,
            @NotNull EvidenceConfidence confidence,
            @Min(0) long version) {}

    public record ReviewDecisionRequest(
            @NotNull ReviewStatus decision,
            @Size(max = 2000) String reason,
            @Min(0) long taskVersion,
            @Min(0) long caseVersion) {}

    public record WorkflowView(List<EvidenceView> evidence, List<ReviewTaskView> reviewTasks) {
        static WorkflowView from(InvestigationWorkflowService.WorkflowSnapshot snapshot) {
            return new WorkflowView(
                    snapshot.evidence().stream().map(EvidenceView::from).toList(),
                    snapshot.reviewTasks().stream().map(ReviewTaskView::from).toList());
        }
    }

    public record EvidenceView(
            UUID id,
            UUID assetId,
            String evidenceType,
            String title,
            String observation,
            EvidenceConclusion conclusion,
            EvidenceConfidence confidence,
            UUID createdBy,
            Instant createdAt) {
        static EvidenceView from(InvestigationEvidence evidence) {
            return new EvidenceView(
                    evidence.id(),
                    evidence.assetId(),
                    evidence.evidenceType(),
                    evidence.title(),
                    evidence.observation(),
                    evidence.conclusion(),
                    evidence.confidence(),
                    evidence.createdBy(),
                    evidence.createdAt());
        }
    }

    public record ReviewTaskView(
            UUID id,
            UUID reviewerId,
            ReviewStatus status,
            String decisionReason,
            UUID createdBy,
            UUID decidedBy,
            long version,
            Instant createdAt,
            Instant decidedAt) {
        static ReviewTaskView from(ReviewTask task) {
            return new ReviewTaskView(
                    task.id(),
                    task.reviewerId(),
                    task.status(),
                    task.decisionReason(),
                    task.createdBy(),
                    task.decidedBy(),
                    task.version(),
                    task.createdAt(),
                    task.decidedAt());
        }
    }
}
