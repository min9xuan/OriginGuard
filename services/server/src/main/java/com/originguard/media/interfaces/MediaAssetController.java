package com.originguard.media.interfaces;

import com.originguard.media.application.MediaAssetService;
import com.originguard.media.domain.MediaAsset;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/assets")
public class MediaAssetController {
    private final MediaAssetService service;

    public MediaAssetController(MediaAssetService service) {
        this.service = service;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('asset:upload')")
    public ResponseEntity<MediaAssetView> register(@Valid @RequestBody RegisterAssetRequest request) {
        MediaAsset asset = service.register(
                request.originalFilename(), request.contentType(), request.byteSize(), request.sha256());
        return ResponseEntity.created(URI.create("/api/v1/assets/" + asset.id()))
                .body(MediaAssetView.from(asset));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('asset:read')")
    public List<MediaAssetView> list() {
        return service.list().stream().map(MediaAssetView::from).toList();
    }

    @GetMapping("/{assetId}")
    @PreAuthorize("hasAuthority('asset:read')")
    public MediaAssetView get(@PathVariable UUID assetId) {
        return MediaAssetView.from(service.get(assetId));
    }

    public record RegisterAssetRequest(
            @NotBlank @Size(max = 255) String originalFilename,
            @NotBlank
                    @Size(max = 127)
                    @Pattern(regexp = "(?i)^image/[a-z0-9.+-]{1,100}$")
                    String contentType,
            @Min(1) @Max(5_368_709_120L) long byteSize,
            @NotBlank @Pattern(regexp = "(?i)^[0-9a-f]{64}$") String sha256) {}

    public record MediaAssetView(
            UUID id,
            UUID tenantId,
            String originalFilename,
            String contentType,
            long byteSize,
            String sha256,
            String storageStatus,
            UUID createdBy,
            Instant createdAt) {
        static MediaAssetView from(MediaAsset asset) {
            return new MediaAssetView(
                    asset.id(),
                    asset.tenantId(),
                    asset.originalFilename(),
                    asset.contentType(),
                    asset.byteSize(),
                    asset.sha256(),
                    asset.storageStatus(),
                    asset.createdBy(),
                    asset.createdAt());
        }
    }
}
