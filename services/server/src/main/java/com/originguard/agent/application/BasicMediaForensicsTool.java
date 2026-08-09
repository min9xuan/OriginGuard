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
public class BasicMediaForensicsTool implements AgentTool {
    public static final String CODE = "media.inspect_basic_forensics";

    private final MediaAssetService mediaAssetService;
    private final MediaContentAnalyzer analyzer;

    public BasicMediaForensicsTool(
            MediaAssetService mediaAssetService,
            MediaContentAnalyzer analyzer) {
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
        long totalBytes = 0;
        boolean allHashesMatch = true;
        for (MediaAsset asset : context.assets()) {
            MediaAssetService.StoredMedia stored =
                    mediaAssetService.readStored(context.actor().tenantId(), asset.id());
            MediaContentAnalyzer.Analysis analysis =
                    analyzer.analyze(stored.content(), stored.mediaObject().detectedContentType());
            boolean shaMatches = asset.sha256().equals(analysis.sha256());
            allHashesMatch &= shaMatches;
            totalBytes += stored.content().length;

            Map<String, Object> finding = new LinkedHashMap<>();
            finding.put("assetId", asset.id().toString());
            finding.put("filename", asset.originalFilename());
            finding.put("registeredContentType", asset.contentType());
            finding.put("detectedContentType", analysis.detectedContentType());
            finding.put("byteSize", stored.content().length);
            finding.put("width", analysis.width());
            finding.put("height", analysis.height());
            finding.put("sha256", analysis.sha256());
            finding.put("sha256MatchesRegistration", shaMatches);
            finding.put("perceptualHashDHash64", analysis.perceptualHash());
            finding.put("extractedMetadata", analysis.extractedMetadata());
            finding.put("c2paStatus", "NOT_CONFIGURED");
            findings.add(Map.copyOf(finding));
        }
        return Map.of(
                "provider", "ORIGINGUARD_INTERNAL",
                "toolVersion", "1.0.0",
                "assetCount", findings.size(),
                "totalBytesRead", totalBytes,
                "fileContentInspected", true,
                "allSha256MatchesRegistration", allHashesMatch,
                "findings", List.copyOf(findings),
                "limitations", List.of(
                        "JPEG and PNG only",
                        "C2PA verifier not configured",
                        "No AIGC classifier or manipulation localization model"));
    }
}
