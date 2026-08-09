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
public class PerceptualSimilarityTool implements AgentTool {
    public static final String CODE = "media.compare_perceptual_similarity";
    private static final int NEAR_DUPLICATE_THRESHOLD = 5;

    private final MediaAssetService mediaAssetService;
    private final MediaContentAnalyzer analyzer;

    public PerceptualSimilarityTool(MediaAssetService mediaAssetService, MediaContentAnalyzer analyzer) {
        this.mediaAssetService = mediaAssetService;
        this.analyzer = analyzer;
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public Map<String, Object> execute(AgentExecutionContext context, Map<String, Object> input) {
        List<AssetHash> hashes = new ArrayList<>();
        for (MediaAsset asset : context.assets()) {
            MediaAssetService.StoredMedia stored =
                    mediaAssetService.readStored(context.actor().tenantId(), asset.id());
            MediaContentAnalyzer.Analysis analysis =
                    analyzer.analyze(stored.content(), stored.mediaObject().detectedContentType());
            hashes.add(new AssetHash(asset.id().toString(), asset.originalFilename(), analysis.perceptualHash()));
        }

        List<Map<String, Object>> comparisons = new ArrayList<>();
        for (int left = 0; left < hashes.size(); left++) {
            for (int right = left + 1; right < hashes.size(); right++) {
                AssetHash a = hashes.get(left);
                AssetHash b = hashes.get(right);
                int distance = Long.bitCount(
                        Long.parseUnsignedLong(a.dHash64(), 16) ^ Long.parseUnsignedLong(b.dHash64(), 16));
                Map<String, Object> comparison = new LinkedHashMap<>();
                comparison.put("leftAssetId", a.assetId());
                comparison.put("rightAssetId", b.assetId());
                comparison.put("hammingDistance", distance);
                comparison.put("classification", distance == 0
                        ? "IDENTICAL_DHASH"
                        : distance <= NEAR_DUPLICATE_THRESHOLD ? "NEAR_DUPLICATE" : "DIFFERENT");
                comparisons.add(Map.copyOf(comparison));
            }
        }

        return Map.of(
                "provider", "ORIGINGUARD_INTERNAL",
                "toolVersion", "1.0.0",
                "algorithm", "dHash64",
                "nearDuplicateThreshold", NEAR_DUPLICATE_THRESHOLD,
                "assetCount", hashes.size(),
                "comparisonCount", comparisons.size(),
                "assetHashes", hashes.stream().map(hash -> Map.of(
                        "assetId", hash.assetId(), "filename", hash.filename(), "dHash64", hash.dHash64())).toList(),
                "comparisons", List.copyOf(comparisons),
                "limitations", List.of("Perceptual similarity is not proof of common origin or AIGC"));
    }

    private record AssetHash(String assetId, String filename, String dHash64) {}
}
