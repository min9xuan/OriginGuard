package com.originguard.agent.application;

import com.originguard.media.infrastructure.ObjectStorage;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class AgentArtifactStorage {
    private static final String CONTENT_TYPE = "image/png";
    private final ObjectStorage objectStorage;

    public AgentArtifactStorage(ObjectStorage objectStorage) {
        this.objectStorage = objectStorage;
    }

    public StoredArtifact storeAttentionOverlay(
            UUID tenantId, UUID taskId, UUID assetId, byte[] content) {
        UUID artifactId = UUID.randomUUID();
        String objectKey = objectKey(tenantId, taskId, assetId, artifactId);
        objectStorage.put(objectKey, content, CONTENT_TYPE);
        return new StoredArtifact(
                artifactId, "AIDE_ATTENTION_OVERLAY", CONTENT_TYPE,
                content.length, sha256(content));
    }

    public byte[] readAttentionOverlay(
            UUID tenantId, UUID taskId, UUID assetId, UUID artifactId) {
        return objectStorage.get(objectKey(tenantId, taskId, assetId, artifactId));
    }

    private String objectKey(UUID tenantId, UUID taskId, UUID assetId, UUID artifactId) {
        return tenantId + "/agent-artifacts/" + taskId + "/" + assetId + "/" + artifactId + ".png";
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record StoredArtifact(
            UUID artifactId, String kind, String contentType, long byteSize, String sha256) {}
}
