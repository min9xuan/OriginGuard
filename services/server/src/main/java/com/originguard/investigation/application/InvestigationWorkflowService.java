package com.originguard.investigation.application;

import com.originguard.audit.application.AuditService;
import com.originguard.identity.application.CurrentActorProvider;
import com.originguard.identity.domain.CurrentActor;
import com.originguard.investigation.domain.AssignableUser;
import com.originguard.investigation.domain.AgentEvidenceCandidate;
import com.originguard.investigation.domain.CaseStatus;
import com.originguard.investigation.domain.EvidenceConclusion;
import com.originguard.investigation.domain.EvidenceConfidence;
import com.originguard.investigation.domain.InvestigationCase;
import com.originguard.investigation.domain.InvestigationEvidence;
import com.originguard.investigation.domain.ReviewStatus;
import com.originguard.investigation.domain.ReviewTask;
import com.originguard.investigation.infrastructure.InvestigationCaseRepository;
import com.originguard.investigation.infrastructure.InvestigationWorkflowRepository;
import com.originguard.shared.application.BusinessConflictException;
import com.originguard.shared.application.ResourceNotFoundException;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InvestigationWorkflowService {
    private static final Set<CaseStatus> ASSIGNABLE_STATUSES = EnumSet.of(
            CaseStatus.DRAFT, CaseStatus.READY, CaseStatus.INVESTIGATING, CaseStatus.REJECTED);

    private final InvestigationCaseRepository caseRepository;
    private final InvestigationWorkflowRepository workflowRepository;
    private final CurrentActorProvider actorProvider;
    private final CaseAccessPolicy accessPolicy;
    private final AuditService auditService;

    public InvestigationWorkflowService(
            InvestigationCaseRepository caseRepository,
            InvestigationWorkflowRepository workflowRepository,
            CurrentActorProvider actorProvider,
            CaseAccessPolicy accessPolicy,
            AuditService auditService) {
        this.caseRepository = caseRepository;
        this.workflowRepository = workflowRepository;
        this.actorProvider = actorProvider;
        this.accessPolicy = accessPolicy;
        this.auditService = auditService;
    }

    public List<AssignableUser> assignableUsers() {
        CurrentActor actor = actorProvider.getRequiredActor();
        return workflowRepository.findAssignableUsers(actor.tenantId());
    }

    public WorkflowSnapshot getWorkflow(UUID caseId) {
        CurrentActor actor = actorProvider.getRequiredActor();
        requireCase(actor.tenantId(), caseId);
        return snapshot(actor.tenantId(), caseId);
    }

    @Transactional
    public InvestigationCase assign(
            UUID caseId, long expectedVersion, UUID investigatorId, UUID reviewerId) {
        CurrentActor actor = actorProvider.getRequiredActor();
        InvestigationCase current = requireCase(actor.tenantId(), caseId);
        if (!ASSIGNABLE_STATUSES.contains(current.status())) {
            throw new BusinessConflictException(
                    "CASE_ASSIGNMENT_NOT_ALLOWED", "Assignments cannot change while a case is under or past review");
        }
        requireRole(actor.tenantId(), investigatorId, "INVESTIGATOR");
        requireRole(actor.tenantId(), reviewerId, "REVIEWER");
        if (investigatorId.equals(reviewerId) || reviewerId.equals(current.createdBy())) {
            throw new BusinessConflictException(
                    "CASE_SELF_REVIEW_NOT_ALLOWED", "The reviewer must differ from the creator and investigator");
        }
        requireVersion(caseRepository.updateAssignment(
                actor.tenantId(), caseId, expectedVersion, investigatorId, reviewerId));
        workflowRepository.insertAssignment(
                actor.tenantId(), caseId, investigatorId, reviewerId, actor.userId());
        auditService.record(
                actor.tenantId(),
                actor.userId(),
                "CASE_ASSIGNMENT_CHANGED",
                InvestigationCaseService.RESOURCE_TYPE,
                caseId,
                Map.of(
                        "investigatorId", investigatorId.toString(),
                        "reviewerId", reviewerId.toString(),
                        "previousVersion", expectedVersion));
        return requireCase(actor.tenantId(), caseId);
    }

    @Transactional
    public InvestigationEvidence addEvidence(
            UUID caseId,
            long expectedVersion,
            UUID assetId,
            String title,
            String observation,
            EvidenceConclusion conclusion,
            EvidenceConfidence confidence) {
        CurrentActor actor = actorProvider.getRequiredActor();
        InvestigationCase current = requireCase(actor.tenantId(), caseId);
        accessPolicy.requireAssignedInvestigator(current, actor);
        if (current.status() != CaseStatus.INVESTIGATING) {
            throw new BusinessConflictException(
                    "EVIDENCE_NOT_EDITABLE", "Evidence can only be added while the case is being investigated");
        }
        if (!workflowRepository.isAssetLinked(actor.tenantId(), caseId, assetId)) {
            throw new BusinessConflictException(
                    "EVIDENCE_ASSET_NOT_LINKED", "Evidence must reference media linked to this case");
        }
        requireVersion(caseRepository.incrementVersion(actor.tenantId(), caseId, expectedVersion));
        UUID evidenceId = UUID.randomUUID();
        InvestigationEvidence evidence = workflowRepository.insertEvidence(
                evidenceId,
                actor.tenantId(),
                caseId,
                assetId,
                title.trim(),
                observation.trim(),
                conclusion,
                confidence,
                actor.userId());
        auditService.record(
                actor.tenantId(),
                actor.userId(),
                "EVIDENCE_ADDED",
                InvestigationCaseService.RESOURCE_TYPE,
                caseId,
                Map.of(
                        "evidenceId", evidenceId.toString(),
                        "assetId", assetId.toString(),
                        "conclusion", conclusion.name(),
                        "confidence", confidence.name()));
        return evidence;
    }

    @Transactional
    public InvestigationEvidence promoteAgentObservation(
            UUID caseId, UUID observationId, long expectedVersion) {
        CurrentActor actor = actorProvider.getRequiredActor();
        InvestigationCase current = requireCase(actor.tenantId(), caseId);
        accessPolicy.requireAssignedInvestigator(current, actor);
        if (current.status() != CaseStatus.INVESTIGATING) {
            throw new BusinessConflictException(
                    "EVIDENCE_NOT_EDITABLE", "Agent observations can only be included during investigation");
        }
        AgentEvidenceCandidate candidate = workflowRepository
                .findAgentEvidenceCandidate(actor.tenantId(), caseId, observationId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "AGENT_OBSERVATION_NOT_FOUND", "Completed Agent observation was not found for this case"));
        if (candidate.promotedEvidenceId() != null) {
            throw new BusinessConflictException(
                    "AGENT_OBSERVATION_ALREADY_INCLUDED", "This Agent observation is already formal case evidence");
        }
        requireVersion(caseRepository.incrementVersion(actor.tenantId(), caseId, expectedVersion));
        UUID evidenceId = UUID.randomUUID();
        InvestigationEvidence evidence = workflowRepository.insertAgentEvidence(
                evidenceId,
                actor.tenantId(),
                caseId,
                candidate,
                titleFor(candidate.evidenceType()),
                candidate.summary() + " 本记录来自确定性 Agent Tool，仅作为辅助事实，不构成 AIGC 结论。",
                actor.userId());
        auditService.record(
                actor.tenantId(), actor.userId(), "AGENT_OBSERVATION_INCLUDED",
                InvestigationCaseService.RESOURCE_TYPE, caseId,
                Map.of(
                        "evidenceId", evidenceId.toString(),
                        "observationId", observationId.toString(),
                        "agentEvidenceType", candidate.evidenceType()));
        return evidence;
    }

    public ReviewTask prepareReviewTask(InvestigationCase current, CurrentActor actor) {
        accessPolicy.requireAssignedInvestigator(current, actor);
        if (workflowRepository.countEvidence(actor.tenantId(), current.id()) == 0) {
            throw new BusinessConflictException(
                    "CASE_EVIDENCE_REQUIRED", "At least one formal evidence record is required before review");
        }
        UUID reviewerId = current.assignedReviewerId();
        if (reviewerId == null) {
            throw new BusinessConflictException(
                    "CASE_REVIEWER_REQUIRED", "An independent reviewer must be assigned before review");
        }
        requireRole(actor.tenantId(), reviewerId, "REVIEWER");
        if (reviewerId.equals(current.createdBy()) || reviewerId.equals(current.assignedInvestigatorId())) {
            throw new BusinessConflictException(
                    "CASE_SELF_REVIEW_NOT_ALLOWED", "The reviewer must differ from the creator and investigator");
        }
        UUID taskId = UUID.randomUUID();
        ReviewTask task = workflowRepository.insertReviewTask(
                taskId, actor.tenantId(), current.id(), reviewerId, actor.userId());
        auditService.record(
                actor.tenantId(),
                actor.userId(),
                "REVIEW_TASK_CREATED",
                InvestigationCaseService.RESOURCE_TYPE,
                current.id(),
                Map.of("reviewTaskId", taskId.toString(), "reviewerId", reviewerId.toString()));
        return task;
    }

    @Transactional
    public WorkflowSnapshot decide(
            UUID caseId,
            UUID taskId,
            long expectedTaskVersion,
            long expectedCaseVersion,
            ReviewStatus decision,
            String reason,
            List<UUID> citedEvidenceIds) {
        CurrentActor actor = actorProvider.getRequiredActor();
        InvestigationCase current = requireCase(actor.tenantId(), caseId);
        if (current.status() != CaseStatus.WAITING_REVIEW) {
            throw new BusinessConflictException(
                    "CASE_NOT_WAITING_REVIEW", "The case is not waiting for a review decision");
        }
        ReviewTask task = workflowRepository.findReviewTask(actor.tenantId(), caseId, taskId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "REVIEW_TASK_NOT_FOUND", "Review task was not found"));
        if (!actor.userId().equals(task.reviewerId())) {
            throw new AccessDeniedException("Only the assigned reviewer can decide this task");
        }
        if (actor.userId().equals(current.createdBy())
                || actor.userId().equals(current.assignedInvestigatorId())) {
            throw new AccessDeniedException("A case cannot be reviewed by its creator or investigator");
        }
        if (decision == ReviewStatus.PENDING) {
            throw new BusinessConflictException(
                    "REVIEW_DECISION_REQUIRED", "Review decision must be APPROVED or REJECTED");
        }
        requireDecisionPermission(actor, decision);
        String normalizedReason = reason == null ? "" : reason.trim();
        if (decision == ReviewStatus.REJECTED && normalizedReason.isBlank()) {
            throw new BusinessConflictException(
                    "REVIEW_REASON_REQUIRED", "A rejection reason is required");
        }
        List<UUID> normalizedEvidenceIds = citedEvidenceIds == null
                ? List.of()
                : citedEvidenceIds.stream().distinct().toList();
        if (normalizedEvidenceIds.isEmpty()) {
            throw new BusinessConflictException(
                    "REVIEW_EVIDENCE_REQUIRED", "The review decision must cite at least one formal case evidence record");
        }
        if (!workflowRepository.evidenceBelongsToCase(actor.tenantId(), caseId, normalizedEvidenceIds)) {
            throw new BusinessConflictException(
                    "REVIEW_EVIDENCE_INVALID", "Cited evidence must belong to the reviewed case");
        }
        if (!workflowRepository.decideReview(
                actor.tenantId(),
                caseId,
                taskId,
                actor.userId(),
                expectedTaskVersion,
                decision,
                normalizedReason)) {
            throw new BusinessConflictException(
                    "REVIEW_VERSION_CONFLICT", "The review task changed; reload and retry");
        }
        workflowRepository.replaceReviewEvidenceReferences(actor.tenantId(), taskId, normalizedEvidenceIds);
        CaseStatus target = decision == ReviewStatus.APPROVED ? CaseStatus.CONFIRMED : CaseStatus.REJECTED;
        requireVersion(caseRepository.updateStatus(
                actor.tenantId(), caseId, expectedCaseVersion, CaseStatus.WAITING_REVIEW, target));
        auditService.record(
                actor.tenantId(),
                actor.userId(),
                decision == ReviewStatus.APPROVED ? "REVIEW_APPROVED" : "REVIEW_REJECTED",
                InvestigationCaseService.RESOURCE_TYPE,
                caseId,
                Map.of(
                        "reviewTaskId", taskId.toString(),
                        "decision", decision.name(),
                        "reason", normalizedReason,
                        "citedEvidenceIds", normalizedEvidenceIds.stream().map(UUID::toString).toList()));
        auditService.record(
                actor.tenantId(),
                actor.userId(),
                "CASE_STATUS_CHANGED",
                InvestigationCaseService.RESOURCE_TYPE,
                caseId,
                Map.of("from", CaseStatus.WAITING_REVIEW.name(), "to", target.name()));
        return snapshot(actor.tenantId(), caseId);
    }

    private WorkflowSnapshot snapshot(UUID tenantId, UUID caseId) {
        return new WorkflowSnapshot(
                workflowRepository.findEvidence(tenantId, caseId),
                workflowRepository.findReviewTasks(tenantId, caseId),
                workflowRepository.findAgentEvidenceCandidates(tenantId, caseId));
    }

    private String titleFor(String evidenceType) {
        return switch (evidenceType) {
            case "FILE_INTEGRITY" -> "Agent：文件完整性核验";
            case "IMAGE_METADATA" -> "Agent：图片元数据观察";
            case "PERCEPTUAL_SIMILARITY" -> "Agent：感知相似度观察";
            default -> "Agent：确定性媒体观察";
        };
    }

    private InvestigationCase requireCase(UUID tenantId, UUID caseId) {
        return caseRepository.findById(tenantId, caseId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "CASE_NOT_FOUND", "Investigation case was not found"));
    }

    private void requireRole(UUID tenantId, UUID userId, String role) {
        if (!workflowRepository.userHasRole(tenantId, userId, role)) {
            throw new BusinessConflictException(
                    "CASE_ASSIGNEE_INVALID", "The selected user is not an enabled " + role.toLowerCase());
        }
    }

    private void requireDecisionPermission(CurrentActor actor, ReviewStatus decision) {
        String permission = decision == ReviewStatus.APPROVED ? "review:approve" : "review:reject";
        if (!actor.hasPermission(permission)) {
            throw new AccessDeniedException("Missing permission: " + permission);
        }
    }

    private void requireVersion(boolean updated) {
        if (!updated) {
            throw new BusinessConflictException(
                    "CASE_VERSION_CONFLICT", "The case was modified by another request; reload and retry");
        }
    }

    public record WorkflowSnapshot(
            List<InvestigationEvidence> evidence,
            List<ReviewTask> reviewTasks,
            List<AgentEvidenceCandidate> agentEvidenceCandidates) {
        public WorkflowSnapshot {
            evidence = List.copyOf(evidence);
            reviewTasks = List.copyOf(reviewTasks);
            agentEvidenceCandidates = List.copyOf(agentEvidenceCandidates);
        }
    }
}
