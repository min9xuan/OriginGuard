package com.originguard.agent.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.originguard.media.application.MediaAssetService;
import com.originguard.media.domain.MediaAsset;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "originguard.agent.media-type-classifier.provider",
        havingValue = "model-api",
        matchIfMissing = true)
public class ModelApiClipMediaTypeTool implements AgentTool {
    private final MediaAssetService mediaAssetService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient client;
    private final URI endpoint;
    private final Duration timeout;

    public ModelApiClipMediaTypeTool(
            MediaAssetService mediaAssetService,
            @Value("${originguard.agent.media-type-classifier.base-url:http://127.0.0.1:8090}")
                    String baseUrl,
            @Value("${originguard.agent.media-type-classifier.timeout:PT2M}") Duration timeout) {
        this.mediaAssetService = mediaAssetService;
        this.timeout = timeout;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        this.endpoint = URI.create(baseUrl.replaceAll("/+$", "") + "/v1/media/classify");
    }

    @Override
    public String code() {
        return ClipMediaTypeTool.CODE;
    }

    @Override
    public Map<String, Object> execute(AgentExecutionContext context, Map<String, Object> input) {
        List<Map<String, Object>> findings = new ArrayList<>();
        for (MediaAsset asset : context.assets()) {
            if (!asset.contentType().startsWith("image/")) continue;
            MediaAssetService.StoredMedia stored =
                    mediaAssetService.readStored(context.actor().tenantId(), asset.id());
            Map<String, Object> classification = classify(stored.content(), asset.contentType());
            Map<String, Object> finding = new LinkedHashMap<>(classification);
            finding.put("assetId", asset.id().toString());
            finding.put("filename", asset.originalFilename());
            findings.add(Map.copyOf(finding));
        }
        if (findings.isEmpty()) {
            throw new IllegalStateException("CLIP media typing requires at least one linked image asset");
        }
        return Map.of(
                "provider", "OPENAI_CLIP",
                "toolVersion", "2.0.0",
                "analyzedImageCount", findings.size(),
                "findings", List.copyOf(findings));
    }

    private Map<String, Object> classify(byte[] content, String contentType) {
        try {
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(timeout)
                    .header("Content-Type", contentType)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(content))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException(
                        "CLIP model API returned HTTP " + response.statusCode() + ": " + response.body());
            }
            return objectMapper.readValue(response.body(), new TypeReference<>() {});
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("CLIP media type request was interrupted", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("CLIP model API is unavailable at " + endpoint, exception);
        }
    }
}
