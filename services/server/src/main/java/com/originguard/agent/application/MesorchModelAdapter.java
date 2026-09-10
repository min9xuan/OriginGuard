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
        name = "originguard.agent.manipulation-localizer.provider",
        havingValue = "model-api",
        matchIfMissing = true)
public class MesorchModelAdapter implements ForensicModelAdapter {
    public static final String CAPABILITY_CODE = "mesorch_manipulation_localization";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient client;
    private final URI endpoint;
    private final Duration timeout;

    public MesorchModelAdapter(
            @Value("${originguard.agent.manipulation-localizer.base-url:http://127.0.0.1:8090}") String baseUrl,
            @Value("${originguard.agent.manipulation-localizer.timeout:PT10M}") Duration timeout) {
        this.timeout = timeout;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        this.endpoint = URI.create(baseUrl.replaceAll("/+$", "") + "/v1/forensics/manipulation/localize");
    }

    @Override
    public ForensicModelCapability capability() {
        return new ForensicModelCapability(
                CAPABILITY_CODE,
                "Mesorch 局部篡改定位",
                "AAAI-2025-official",
                "MANIPULATION_LOCALIZATION",
                Set.of("IMAGE"),
                Set.of(ForensicModelCapability.ANY_MEDIA_TYPE),
                Set.of("TAMPER_PROBABILITY", "LOCALIZATION_MASK", "HEATMAP", "OVERLAY", "REGIONS"),
                100,
                "定位拼接、复制移动、擦除、修复与局部生成等疑似内容变化区域。",
                List.of("业务阈值尚未校准", "定位结果不能说明编辑工具或责任主体"));
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
                throw new IllegalStateException("Manipulation localization API returned HTTP "
                        + response.statusCode() + ": " + response.body());
            }
            return objectMapper.readValue(response.body(), new TypeReference<>() {});
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Manipulation localization request was interrupted", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("Manipulation localization API is unavailable at " + endpoint, exception);
        }
    }
}
