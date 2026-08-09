package com.originguard.agent.application;

import com.originguard.media.application.MediaAssetService;
import com.originguard.media.application.MediaContentAnalyzer;
import com.originguard.media.domain.MediaAsset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class ImageMetadataTool implements AgentTool {
    public static final String CODE = "media.extract_image_metadata";

    private final MediaAssetService mediaAssetService;
    private final MediaContentAnalyzer analyzer;

    public ImageMetadataTool(MediaAssetService mediaAssetService, MediaContentAnalyzer analyzer) {
        this.mediaAssetService = mediaAssetService;
        this.analyzer = analyzer;
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public Map<String, Object> execute(AgentExecutionContext context, Map<String, Object> input) {
        List<Map<String, Object>> findings = new ArrayList<>();
        for (MediaAsset asset : context.assets()) {
            MediaAssetService.StoredMedia stored =
                    mediaAssetService.readStored(context.actor().tenantId(), asset.id());
            MediaContentAnalyzer.Analysis analysis =
                    analyzer.analyze(stored.content(), stored.mediaObject().detectedContentType());
            Map<String, Object> finding = new LinkedHashMap<>();
            finding.put("assetId", asset.id().toString());
            finding.put("filename", asset.originalFilename());
            finding.put("detectedContentType", analysis.detectedContentType());
            finding.put("width", analysis.width());
            finding.put("height", analysis.height());
            finding.put("megapixels", Math.round(analysis.width() * (double) analysis.height() / 10_000.0) / 100.0);
            finding.put("extractedMetadata", analysis.extractedMetadata());
            finding.put("hasExtractedMetadata", !analysis.extractedMetadata().isEmpty());
            findings.add(Map.copyOf(finding));
        }
        return Map.of(
                "provider", "ORIGINGUARD_INTERNAL",
                "toolVersion", "1.0.0",
                "assetCount", findings.size(),
                "findings", List.copyOf(findings),
                "limitations", List.of("Only selected EXIF fields are retained", "Missing EXIF is not evidence of AIGC"));
    }
}
