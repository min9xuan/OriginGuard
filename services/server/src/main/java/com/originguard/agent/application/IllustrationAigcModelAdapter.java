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
@ConditionalOnProperty(name = "originguard.agent.illustration-detector.enabled", havingValue = "true")
public class IllustrationAigcModelAdapter implements ForensicModelAdapter {
    public static final String CAPABILITY_CODE = "illustration_aigc_detection";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient client;
    private final URI endpoint;
    private final Duration timeout;

    public IllustrationAigcModelAdapter(
            @Value("${originguard.agent.illustration-detector.base-url:http://127.0.0.1:8090}") String baseUrl,
            @Value("${originguard.agent.illustration-detector.timeout:PT10M}") Duration timeout) {
        this.timeout = timeout;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        this.endpoint = URI.create(baseUrl.replaceAll("/+$", "") + "/v1/aigc/anime/detect");
    }

    @Override
    public ForensicModelCapability capability() {
        return new ForensicModelCapability(
                CAPABILITY_CODE,
                "插画与卡通生成内容鉴别",
                "1.0.0",
                "AIGC_DETECTION",
                Set.of("IMAGE"),
                Set.of("ILLUSTRATION_CARTOON"),
                Set.of("PROBABILITY", "VERDICT", "LOCALIZATION_MASK"),
                100,
                "面向动漫、插画和卡通内容的生成痕迹检测与区域定位能力。",
                List.of("模型分数和阈值需要使用业务域验证集校准"));
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
                throw new IllegalStateException("Illustration detection API returned HTTP "
                        + response.statusCode() + ": " + response.body());
            }
            return objectMapper.readValue(response.body(), new TypeReference<>() {});
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Illustration detection request was interrupted", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("Illustration detection API is unavailable at " + endpoint, exception);
        }
    }
}
