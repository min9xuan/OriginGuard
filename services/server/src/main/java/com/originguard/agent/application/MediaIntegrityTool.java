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
public class MediaIntegrityTool implements AgentTool {
    public static final String CODE = "media.verify_integrity";

    private final MediaAssetService mediaAssetService;
    private final MediaContentAnalyzer analyzer;

    public MediaIntegrityTool(MediaAssetService mediaAssetService, MediaContentAnalyzer analyzer) {
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
        boolean allChecksPassed = true;
        for (MediaAsset asset : context.assets()) {
            MediaAssetService.StoredMedia stored =
                    mediaAssetService.readStored(context.actor().tenantId(), asset.id());
            MediaContentAnalyzer.Analysis analysis =
                    analyzer.analyze(stored.content(), stored.mediaObject().detectedContentType());
            boolean shaMatches = asset.sha256().equals(analysis.sha256());
            boolean sizeMatches = asset.byteSize() == stored.content().length;
            boolean mimeMatches = asset.contentType().equalsIgnoreCase(analysis.detectedContentType())
                    && stored.mediaObject().detectedContentType().equalsIgnoreCase(analysis.detectedContentType());
            allChecksPassed &= shaMatches && sizeMatches && mimeMatches;

            Map<String, Object> finding = new LinkedHashMap<>();
            finding.put("assetId", asset.id().toString());
            finding.put("filename", asset.originalFilename());
            finding.put("registeredSha256", asset.sha256());
            finding.put("observedSha256", analysis.sha256());
            finding.put("sha256Matches", shaMatches);
            finding.put("registeredByteSize", asset.byteSize());
            finding.put("observedByteSize", stored.content().length);
            finding.put("byteSizeMatches", sizeMatches);
            finding.put("registeredContentType", asset.contentType());
            finding.put("detectedContentType", analysis.detectedContentType());
            finding.put("contentTypeMatches", mimeMatches);
            finding.put("integrityPassed", shaMatches && sizeMatches && mimeMatches);
            findings.add(Map.copyOf(finding));
        }
        return Map.of(
                "provider", "ORIGINGUARD_INTERNAL",
                "toolVersion", "1.0.0",
                "assetCount", findings.size(),
                "allChecksPassed", allChecksPassed,
                "findings", List.copyOf(findings));
    }
}
