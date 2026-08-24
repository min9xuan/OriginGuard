package com.originguard.agent.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "originguard.agent.aigc-detector.provider",
        havingValue = "model-api",
        matchIfMissing = true)
public class GenericAigcModelAdapter implements ForensicModelAdapter {
    public static final String CAPABILITY_CODE = "generic_image_aigc_detection";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient client;
    private final URI endpoint;
    private final Duration timeout;

    public GenericAigcModelAdapter(
            @Value("${originguard.agent.aigc-detector.base-url:http://127.0.0.1:8090}") String baseUrl,
            @Value("${originguard.agent.aigc-detector.timeout:PT10M}") Duration timeout) {
        this.timeout = timeout;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        this.endpoint = URI.create(baseUrl.replaceAll("/+$", "") + "/v1/aigc/detect");
    }

    @Override
    public ForensicModelCapability capability() {
        return new ForensicModelCapability(
                CAPABILITY_CODE,
                "通用图像生成内容鉴别",
                "1.0.0",
                "AIGC_DETECTION",
                Set.of("IMAGE"),
                Set.of(ForensicModelCapability.ANY_MEDIA_TYPE),
                Set.of("PROBABILITY", "VERDICT", "ATTENTION_MAP", "QUALITY_ASSESSMENT"),
                10,
                "对常见图像执行通用生成内容鉴别，作为缺少专用模型时的基础能力。",
                List.of("跨生成器和特殊视觉类型的泛化能力需要验证集校准"));
    }

    @Override
    public Map<String, Object> analyze(byte[] content, String contentType) {
        try {
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(timeout)
                    .header("Content-Type", contentType)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(content))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException(
                        "AIGC detection API returned HTTP " + response.statusCode() + ": " + response.body());
            }
            return objectMapper.readValue(response.body(), new TypeReference<>() {});
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("AIGC detection request was interrupted", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("AIGC detection API is unavailable at " + endpoint, exception);
        }
    }
}
