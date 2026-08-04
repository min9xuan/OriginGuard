package com.originguard.media.application;

import com.originguard.audit.application.AuditService;
import com.originguard.identity.application.CurrentActorProvider;
import com.originguard.media.domain.MediaAsset;
import com.originguard.media.infrastructure.MediaAssetRepository;
import com.originguard.shared.application.BusinessConflictException;
import com.originguard.shared.application.ResourceNotFoundException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MediaAssetService {
    public static final String RESOURCE_TYPE = "MEDIA_ASSET";

    private final MediaAssetRepository repository;
    private final CurrentActorProvider actorProvider;
    private final AuditService auditService;

    public MediaAssetService(
            MediaAssetRepository repository,
            CurrentActorProvider actorProvider,
            AuditService auditService) {
        this.repository = repository;
        this.actorProvider = actorProvider;
        this.auditService = auditService;
    }

    @Transactional
    public MediaAsset register(
            String originalFilename, String contentType, long byteSize, String rawSha256) {
        var actor = actorProvider.getRequiredActor();
        String sha256 = rawSha256.toLowerCase(Locale.ROOT);
        repository.findBySha256(actor.tenantId(), sha256).ifPresent(existing -> {
            throw duplicate(existing.id());
        });

        UUID id = UUID.randomUUID();
        try {
            MediaAsset asset = repository.insert(
                    id,
                    actor.tenantId(),
                    originalFilename.trim(),
                    contentType.trim().toLowerCase(Locale.ROOT),
                    byteSize,
                    sha256,
                    actor.userId());
            auditService.record(
                    actor.tenantId(),
                    actor.userId(),
                    "MEDIA_ASSET_REGISTERED",
                    RESOURCE_TYPE,
                    id,
                    Map.of("sha256", sha256, "originalFilename", asset.originalFilename()));
            return asset;
        } catch (DuplicateKeyException exception) {
            throw new BusinessConflictException(
                    "ASSET_SHA256_CONFLICT", "An asset with the same SHA-256 already exists");
        }
    }

    public List<MediaAsset> list() {
        var actor = actorProvider.getRequiredActor();
        return repository.findAll(actor.tenantId());
    }

    public MediaAsset get(UUID id) {
        var actor = actorProvider.getRequiredActor();
        return require(actor.tenantId(), id);
    }

    public MediaAsset require(UUID tenantId, UUID id) {
        return repository.findById(tenantId, id)
                .orElseThrow(() -> new ResourceNotFoundException("ASSET_NOT_FOUND", "Media asset was not found"));
    }

    private BusinessConflictException duplicate(UUID id) {
        return new BusinessConflictException(
                "ASSET_SHA256_CONFLICT", "An asset with the same SHA-256 already exists: " + id);
    }
}
