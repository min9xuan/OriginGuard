package com.originguard.investigation.application;

import com.originguard.audit.application.AuditService;
import com.originguard.audit.domain.AuditEntry;
import com.originguard.identity.application.CurrentActorProvider;
import com.originguard.identity.domain.CurrentActor;
import com.originguard.investigation.domain.CasePriority;
import com.originguard.investigation.domain.CaseStatus;
import com.originguard.investigation.domain.InvestigationCase;
import com.originguard.investigation.infrastructure.InvestigationCaseRepository;
import com.originguard.media.application.MediaAssetService;
import com.originguard.media.domain.MediaAsset;
import com.originguard.shared.application.BusinessConflictException;
import com.originguard.shared.application.ResourceNotFoundException;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
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
public class InvestigationCaseService {
    public static final String RESOURCE_TYPE = "INVESTIGATION_CASE";
    private static final Set<CaseStatus> M1_TRANSITION_TARGETS =
            EnumSet.of(CaseStatus.READY, CaseStatus.INVESTIGATING, CaseStatus.WAITING_REVIEW);
    private static final DateTimeFormatter CASE_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    private final InvestigationCaseRepository repository;
    private final MediaAssetService mediaAssetService;
    private final CurrentActorProvider actorProvider;
    private final CaseAccessPolicy accessPolicy;
    private final InvestigationWorkflowService workflowService;
    private final AuditService auditService;
    private final Clock clock;

    public InvestigationCaseService(
            InvestigationCaseRepository repository,
            MediaAssetService mediaAssetService,
            CurrentActorProvider actorProvider,
            CaseAccessPolicy accessPolicy,
            InvestigationWorkflowService workflowService,
            AuditService auditService,
            Clock clock) {
        this.repository = repository;
        this.mediaAssetService = mediaAssetService;
        this.actorProvider = actorProvider;
        this.accessPolicy = accessPolicy;
        this.workflowService = workflowService;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional
    public CaseDetails create(
            String title, String description, CasePriority priority, List<UUID> assetIds) {
        CurrentActor actor = actorProvider.getRequiredActor();
        List<MediaAsset> assets = assetIds.stream()
                .distinct()
                .map(id -> mediaAssetService.require(actor.tenantId(), id))
                .toList();
        UUID id = UUID.randomUUID();
        String caseNumber = "OG-" + CASE_DATE.format(clock.instant()) + "-"
                + id.toString().substring(0, 8).toUpperCase();
        InvestigationCase created = repository.insert(
                id,
                actor.tenantId(),
                caseNumber,
                title.trim(),
                normalizeDescription(description),
                priority,
                actor.userId());
        for (MediaAsset asset : assets) {
            repository.linkAsset(id, asset.id(), actor.userId());
        }
        auditService.record(
                actor.tenantId(),
                actor.userId(),
                "CASE_CREATED",
                RESOURCE_TYPE,
                id,
                Map.of("caseNumber", caseNumber, "assetCount", assets.size()));
        return new CaseDetails(created, assets);
    }

    public List<InvestigationCase> list() {
        CurrentActor actor = actorProvider.getRequiredActor();
        return repository.findAll(actor.tenantId());
    }

    public CaseDetails get(UUID id) {
        CurrentActor actor = actorProvider.getRequiredActor();
        InvestigationCase investigationCase = require(actor.tenantId(), id);
        return details(investigationCase);
    }

    @Transactional
    public CaseDetails update(
            UUID id, long expectedVersion, String title, String description, CasePriority priority) {
        CurrentActor actor = actorProvider.getRequiredActor();
        InvestigationCase current = require(actor.tenantId(), id);
        accessPolicy.requireCanModify(current, actor);
        if (current.status() != CaseStatus.DRAFT && current.status() != CaseStatus.REJECTED) {
            throw new BusinessConflictException(
                    "CASE_NOT_EDITABLE", "Only draft or rejected cases can be edited");
        }
        boolean updated = repository.updateDetails(
                actor.tenantId(),
                id,
                expectedVersion,
                title.trim(),
                normalizeDescription(description),
                priority);
        requireVersion(updated);
        auditService.record(
                actor.tenantId(),
                actor.userId(),
                "CASE_UPDATED",
                RESOURCE_TYPE,
                id,
                Map.of("previousVersion", expectedVersion));
        return details(require(actor.tenantId(), id));
    }

    @Transactional
    public CaseDetails linkAsset(UUID caseId, UUID assetId, long expectedVersion) {
        CurrentActor actor = actorProvider.getRequiredActor();
        InvestigationCase current = require(actor.tenantId(), caseId);
        accessPolicy.requireCanModify(current, actor);
        if (current.status() != CaseStatus.DRAFT && current.status() != CaseStatus.REJECTED) {
            throw new BusinessConflictException(
                    "CASE_ASSET_LINK_NOT_ALLOWED", "Assets can only be linked to draft or rejected cases");
        }
        MediaAsset asset = mediaAssetService.require(actor.tenantId(), assetId);
        if (repository.isAssetLinked(caseId, assetId)) {
            return details(current);
        }
        requireVersion(repository.incrementVersion(actor.tenantId(), caseId, expectedVersion));
        repository.linkAsset(caseId, assetId, actor.userId());
        auditService.record(
                actor.tenantId(),
                actor.userId(),
                "CASE_ASSET_LINKED",
                RESOURCE_TYPE,
                caseId,
                Map.of("assetId", asset.id().toString(), "previousVersion", expectedVersion));
        return details(require(actor.tenantId(), caseId));
    }

    @Transactional
    public CaseDetails transition(UUID id, long expectedVersion, CaseStatus target) {
        CurrentActor actor = actorProvider.getRequiredActor();
        InvestigationCase current = require(actor.tenantId(), id);
        accessPolicy.requireCanModify(current, actor);
        if (!M1_TRANSITION_TARGETS.contains(target)) {
            throw new BusinessConflictException(
                    "CASE_TRANSITION_NOT_AVAILABLE",
                    "This transition belongs to the human review or archival milestone");
        }
        requireTransitionPermission(actor, target);
        if (!current.status().canTransitionTo(target)) {
            throw new BusinessConflictException(
                    "CASE_STATUS_CONFLICT",
                    "Cannot transition case from " + current.status() + " to " + target);
        }
        if (target == CaseStatus.READY && repository.countAssets(id) == 0) {
            throw new BusinessConflictException(
                    "CASE_ASSET_REQUIRED", "At least one media asset is required before a case is ready");
        }
        boolean updated = repository.updateStatus(
                actor.tenantId(), id, expectedVersion, current.status(), target);
        requireVersion(updated);
        if (target == CaseStatus.WAITING_REVIEW) {
            workflowService.prepareReviewTask(current, actor);
        }
        auditService.record(
                actor.tenantId(),
                actor.userId(),
                "CASE_STATUS_CHANGED",
                RESOURCE_TYPE,
                id,
                Map.of("from", current.status().name(), "to", target.name()));
        return details(require(actor.tenantId(), id));
    }

    public List<AuditEntry> history(UUID id) {
        CurrentActor actor = actorProvider.getRequiredActor();
        require(actor.tenantId(), id);
        return auditService.history(actor.tenantId(), RESOURCE_TYPE, id);
    }

    private InvestigationCase require(UUID tenantId, UUID id) {
        return repository.findById(tenantId, id)
                .orElseThrow(() -> new ResourceNotFoundException("CASE_NOT_FOUND", "Investigation case was not found"));
    }

    private CaseDetails details(InvestigationCase investigationCase) {
        return new CaseDetails(
                investigationCase,
                repository.findAssets(investigationCase.tenantId(), investigationCase.id()));
    }

    private void requireTransitionPermission(CurrentActor actor, CaseStatus target) {
        String permission = target == CaseStatus.WAITING_REVIEW ? "case:submit" : "case:update";
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

    private String normalizeDescription(String description) {
        return description == null ? "" : description.trim();
    }

    public record CaseDetails(InvestigationCase investigationCase, List<MediaAsset> assets) {
        public CaseDetails {
            assets = List.copyOf(assets);
        }
    }
}
