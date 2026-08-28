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
import com.originguard.investigation.domain.CaseDecision;
import com.originguard.investigation.domain.ConfirmationStatus;
import com.originguard.investigation.infrastructure.InvestigationCaseRepository;
import com.originguard.investigation.infrastructure.InvestigationWorkflowRepository;
import com.originguard.shared.application.BusinessConflictException;
import com.originguard.shared.application.ResourceNotFoundException;
import java.util.EnumSet;
import java.util.LinkedHashMap;
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
            CaseStatus.DRAFT, CaseStatus.READY, CaseStatus.INVESTIGATING);

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
            UUID caseId, long expectedVersion, UUID investigatorId) {
        CurrentActor actor = actorProvider.getRequiredActor();
        InvestigationCase current = requireCase(actor.tenantId(), caseId);
        if (!ASSIGNABLE_STATUSES.contains(current.status())) {
            throw new BusinessConflictException(
                    "CASE_ASSIGNMENT_NOT_ALLOWED", "Assignments cannot change while a result is awaiting confirmation or completed");
        }
        requireRole(actor.tenantId(), investigatorId, "INVESTIGATOR");
        requireVersion(caseRepository.updateAssignment(
                actor.tenantId(), caseId, expectedVersion, investigatorId));
        workflowRepository.insertAssignment(
                actor.tenantId(), caseId, investigatorId, actor.userId());
        auditService.record(
                actor.tenantId(),
                actor.userId(),
                "CASE_ASSIGNMENT_CHANGED",
                InvestigationCaseService.RESOURCE_TYPE,
                caseId,
                Map.of(
                        "investigatorId", investigatorId.toString(),
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

    public CaseDecision prepareDecision(InvestigationCase current, CurrentActor actor) {
        accessPolicy.requireAssignedInvestigator(current, actor);
        if (workflowRepository.countEvidence(actor.tenantId(), current.id()) == 0) {
            throw new BusinessConflictException(
                    "CASE_EVIDENCE_REQUIRED", "At least one formal evidence record is required before result confirmation");
        }
        return insertDecision(current, actor);
    }

    public CaseDecision prepareDecisionAfterAgent(
            InvestigationCase current, CurrentActor actor, UUID agentTaskId) {
        accessPolicy.requireAssignedInvestigator(current, actor);
        workflowRepository.findCompletedAgentAssessment(actor.tenantId(), current.id(), agentTaskId)
                .orElseThrow(() -> new BusinessConflictException(
                        "RESULT_AGENT_TASK_INVALID",
                        "A completed Agent assessment is required before user confirmation"));
        return insertDecision(current, actor);
    }

    private CaseDecision insertDecision(InvestigationCase current, CurrentActor actor) {
        UUID confirmerId = current.assignedInvestigatorId();
        if (confirmerId == null) {
            throw new BusinessConflictException(
                    "CASE_INVESTIGATOR_REQUIRED", "An investigator must be assigned before result confirmation");
        }
        requireRole(actor.tenantId(), confirmerId, "INVESTIGATOR");
        UUID decisionId = UUID.randomUUID();
        CaseDecision decision = workflowRepository.insertDecision(
                decisionId, actor.tenantId(), current.id(), confirmerId, actor.userId());
        auditService.record(
                actor.tenantId(),
                actor.userId(),
                "RESULT_CONFIRMATION_CREATED",
                InvestigationCaseService.RESOURCE_TYPE,
                current.id(),
                Map.of("decisionId", decisionId.toString(), "confirmerId", confirmerId.toString()));
        return decision;
    }

    @Transactional
    public WorkflowSnapshot decide(
            UUID caseId,
            UUID decisionId,
            long expectedTaskVersion,
            long expectedCaseVersion,
            EvidenceConclusion finalConclusion,
            String reason,
            List<UUID> citedEvidenceIds,
            boolean includeAgentAssessment,
            UUID agentTaskId) {
        CurrentActor actor = actorProvider.getRequiredActor();
        InvestigationCase current = requireCase(actor.tenantId(), caseId);
        if (current.status() != CaseStatus.WAITING_CONFIRMATION) {
            throw new BusinessConflictException(
                    "CASE_NOT_WAITING_CONFIRMATION", "The case is not waiting for investigator confirmation");
        }
        CaseDecision task = workflowRepository.findDecision(actor.tenantId(), caseId, decisionId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "CASE_DECISION_NOT_FOUND", "Case decision was not found"));
        if (!actor.userId().equals(task.confirmerId())
                || !actor.userId().equals(current.assignedInvestigatorId())) {
            throw new AccessDeniedException("Only the assigned investigator can confirm this result");
        }
        ConfirmationStatus decision = finalConclusion == EvidenceConclusion.INCONCLUSIVE
                ? ConfirmationStatus.RETURNED
                : ConfirmationStatus.CONFIRMED;
        requireDecisionPermission(actor);
        String normalizedReason = reason == null ? "" : reason.trim();
        if (normalizedReason.isBlank()) {
            normalizedReason = switch (finalConclusion) {
                case LIKELY_SYNTHETIC -> "人工复核后判定该媒体为 AI 生成内容。";
                case LIKELY_AUTHENTIC -> "人工复核后判定该媒体为非 AI 生成内容。";
                case INCONCLUSIVE -> "现有证据不足以形成最终判断，退回补充调查。";
            };
        }
        List<UUID> normalizedEvidenceIds = citedEvidenceIds == null
                ? List.of()
                : citedEvidenceIds.stream().distinct().toList();
        if (!normalizedEvidenceIds.isEmpty()
                && !workflowRepository.evidenceBelongsToCase(actor.tenantId(), caseId, normalizedEvidenceIds)) {
            throw new BusinessConflictException(
                    "RESULT_EVIDENCE_INVALID", "Cited evidence must belong to the case");
        }
        Map<String, Object> agentAssessmentSnapshot = Map.of();
        UUID includedAgentTaskId = null;
        if (includeAgentAssessment) {
            if (agentTaskId == null) {
                throw new BusinessConflictException(
                        "RESULT_AGENT_TASK_REQUIRED", "A completed Agent task must be selected for inclusion");
            }
            agentAssessmentSnapshot = workflowRepository
                    .findCompletedAgentAssessment(actor.tenantId(), caseId, agentTaskId)
                    .orElseThrow(() -> new BusinessConflictException(
                            "RESULT_AGENT_TASK_INVALID",
                            "The selected Agent assessment must be a completed task for this case"));
            includedAgentTaskId = agentTaskId;
        }
        if (!workflowRepository.confirmDecision(
                actor.tenantId(),
                caseId,
                decisionId,
                actor.userId(),
                expectedTaskVersion,
                decision,
                finalConclusion,
                normalizedReason,
                includedAgentTaskId,
                agentAssessmentSnapshot)) {
            throw new BusinessConflictException(
                    "DECISION_VERSION_CONFLICT", "The result confirmation changed; reload and retry");
        }
        workflowRepository.replaceDecisionEvidenceReferences(actor.tenantId(), decisionId, normalizedEvidenceIds);
        CaseStatus target = decision == ConfirmationStatus.CONFIRMED ? CaseStatus.COMPLETED : CaseStatus.INVESTIGATING;
        requireVersion(caseRepository.updateStatus(
                actor.tenantId(), caseId, expectedCaseVersion, CaseStatus.WAITING_CONFIRMATION, target));
        Map<String, Object> reviewAuditDetails = new LinkedHashMap<>();
        reviewAuditDetails.put("decisionId", decisionId.toString());
        reviewAuditDetails.put("decision", decision.name());
        reviewAuditDetails.put("finalConclusion", finalConclusion.name());
        reviewAuditDetails.put("reason", normalizedReason);
        reviewAuditDetails.put("citedEvidenceIds", normalizedEvidenceIds.stream().map(UUID::toString).toList());
        reviewAuditDetails.put("agentAssessmentIncluded", includeAgentAssessment);
        if (includedAgentTaskId != null) {
            reviewAuditDetails.put("agentTaskId", includedAgentTaskId.toString());
        }
        auditService.record(
                actor.tenantId(),
                actor.userId(),
                decision == ConfirmationStatus.CONFIRMED ? "RESULT_CONFIRMED" : "RESULT_RETURNED",
                InvestigationCaseService.RESOURCE_TYPE,
                caseId,
                Map.copyOf(reviewAuditDetails));
        auditService.record(
                actor.tenantId(),
                actor.userId(),
                "CASE_STATUS_CHANGED",
                InvestigationCaseService.RESOURCE_TYPE,
                caseId,
                Map.of("from", CaseStatus.WAITING_CONFIRMATION.name(), "to", target.name()));
        return snapshot(actor.tenantId(), caseId);
    }

    private WorkflowSnapshot snapshot(UUID tenantId, UUID caseId) {
        return new WorkflowSnapshot(
                workflowRepository.findEvidence(tenantId, caseId),
                workflowRepository.findDecisions(tenantId, caseId),
                workflowRepository.findAgentEvidenceCandidates(tenantId, caseId));
    }

    private String titleFor(String evidenceType) {
        return switch (evidenceType) {
            case "FILE_INTEGRITY" -> "Agent：文件完整性核验";
            case "IMAGE_METADATA" -> "Agent：图片元数据观察";
            case "PERCEPTUAL_SIMILARITY" -> "Agent：感知相似度观察";
            case "CONTENT_PROVENANCE" -> "Agent：C2PA 内容来源凭证";
            case "RAG_GUIDANCE" -> "Agent：RAG 取证指引引用";
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

    private void requireDecisionPermission(CurrentActor actor) {
        if (!actor.hasPermission("result:confirm")) {
            throw new AccessDeniedException("Missing permission: result:confirm");
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
            List<CaseDecision> decisions,
            List<AgentEvidenceCandidate> agentEvidenceCandidates) {
        public WorkflowSnapshot {
            evidence = List.copyOf(evidence);
            decisions = List.copyOf(decisions);
            agentEvidenceCandidates = List.copyOf(agentEvidenceCandidates);
        }
    }
}
