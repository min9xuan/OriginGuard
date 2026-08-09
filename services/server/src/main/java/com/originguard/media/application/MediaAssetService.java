package com.originguard.media.application;

import com.originguard.audit.application.AuditService;
import com.originguard.identity.application.CurrentActorProvider;
import com.originguard.media.domain.MediaAsset;
import com.originguard.media.domain.MediaObject;
import com.originguard.media.infrastructure.MediaAssetRepository;
import com.originguard.media.infrastructure.MediaObjectRepository;
import com.originguard.media.infrastructure.ObjectStorage;
import com.originguard.media.infrastructure.StorageProperties;
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
    private final MediaObjectRepository objectRepository;
    private final ObjectStorage objectStorage;
    private final MediaContentAnalyzer analyzer;
    private final StorageProperties storageProperties;

    public MediaAssetService(
            MediaAssetRepository repository,
            CurrentActorProvider actorProvider,
            AuditService auditService,
            MediaObjectRepository objectRepository,
            ObjectStorage objectStorage,
            MediaContentAnalyzer analyzer,
            StorageProperties storageProperties) {
        this.repository = repository;
        this.actorProvider = actorProvider;
        this.auditService = auditService;
        this.objectRepository = objectRepository;
        this.objectStorage = objectStorage;
        this.analyzer = analyzer;
        this.storageProperties = storageProperties;
    }

    @Transactional
    public MediaAsset upload(
            String originalFilename,
            String declaredContentType,
            byte[] content,
            String clientSha256) {
        var actor = actorProvider.getRequiredActor();
        String normalizedFilename = originalFilename == null ? "" : originalFilename.trim();
        if (normalizedFilename.isBlank() || normalizedFilename.length() > 255) {
            throw new BusinessConflictException(
                    "MEDIA_CONTENT_INVALID", "Media filename must contain 1 to 255 characters");
        }
        if (content.length > storageProperties.maxUploadBytes()) {
            throw new BusinessConflictException(
                    "MEDIA_TOO_LARGE", "Media exceeds the configured upload limit");
        }
        MediaContentAnalyzer.Analysis analysis = analyzer.analyze(content, declaredContentType);
        if (clientSha256 != null
                && !clientSha256.isBlank()
                && !analysis.sha256().equalsIgnoreCase(clientSha256)) {
            throw new BusinessConflictException(
                    "MEDIA_SHA256_MISMATCH", "Browser and server SHA-256 values do not match");
        }
        repository.findBySha256(actor.tenantId(), analysis.sha256()).ifPresent(existing -> {
            throw duplicate(existing.id());
        });

        UUID id = UUID.randomUUID();
        String objectKey = actor.tenantId() + "/" + id;
        objectStorage.put(objectKey, content, analysis.detectedContentType());
        try {
            MediaAsset asset = repository.insert(
                    id,
                    actor.tenantId(),
                    normalizedFilename,
                    analysis.detectedContentType(),
                    content.length,
                    analysis.sha256(),
                    actor.userId());
            objectRepository.insert(actor.tenantId(), id, objectKey, analysis);
            repository.markStored(actor.tenantId(), id);
            MediaAsset stored = repository.findById(actor.tenantId(), id).orElseThrow();
            auditService.record(
                    actor.tenantId(),
                    actor.userId(),
                    "MEDIA_ASSET_STORED",
                    RESOURCE_TYPE,
                    id,
                    Map.of(
                            "sha256", analysis.sha256(),
                            "detectedContentType", analysis.detectedContentType(),
                            "width", analysis.width(),
                            "height", analysis.height(),
                            "perceptualHash", analysis.perceptualHash()));
            return stored;
        } catch (RuntimeException exception) {
            objectStorage.remove(objectKey);
            throw exception;
        }
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

    public StoredMedia readStored(UUID tenantId, UUID id) {
        MediaAsset asset = require(tenantId, id);
        MediaObject mediaObject = objectRepository.find(tenantId, id)
                .orElseThrow(() -> new BusinessConflictException(
                        "MEDIA_CONTENT_NOT_STORED", "Media content has not been uploaded"));
        return new StoredMedia(asset, mediaObject, objectStorage.get(mediaObject.objectKey()));
    }

    public StoredMedia readStored(UUID id) {
        var actor = actorProvider.getRequiredActor();
        return readStored(actor.tenantId(), id);
    }

    private BusinessConflictException duplicate(UUID id) {
        return new BusinessConflictException(
                "ASSET_SHA256_CONFLICT", "An asset with the same SHA-256 already exists: " + id);
    }

    public record StoredMedia(MediaAsset asset, MediaObject mediaObject, byte[] content) {
        public StoredMedia {
            content = content.clone();
        }
    }
}
