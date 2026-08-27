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
@ConditionalOnProperty(name = "originguard.agent.diffusion-verifier.enabled", havingValue = "true")
public class DiffusionReconstructionModelAdapter implements ForensicModelAdapter {
    public static final String CAPABILITY_CODE = "diffusion_reconstruction_verification";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient client;
    private final URI endpoint;
    private final Duration timeout;

    public DiffusionReconstructionModelAdapter(
            @Value("${originguard.agent.diffusion-verifier.base-url:http://127.0.0.1:8090}") String baseUrl,
            @Value("${originguard.agent.diffusion-verifier.timeout:PT15M}") Duration timeout) {
        this.timeout = timeout;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        this.endpoint = URI.create(baseUrl.replaceAll("/+$", "") + "/v1/aigc/diffusion/reconstruct");
    }

    @Override
    public ForensicModelCapability capability() {
        return new ForensicModelCapability(
                CAPABILITY_CODE,
                "扩散重建痕迹复核",
                "1.0.0",
                "DIFFUSION_RECONSTRUCTION_VERIFICATION",
                Set.of("IMAGE"),
                Set.of(ForensicModelCapability.ANY_MEDIA_TYPE),
                Set.of("RECONSTRUCTION_DISTANCE", "AUXILIARY_VERDICT"),
                50,
                "使用扩散自编码器重建距离复核潜在扩散模型生成痕迹。",
                List.of("重建距离不是生成概率，业务阈值需要单独校准"));
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
                throw new IllegalStateException("Diffusion verification API returned HTTP "
                        + response.statusCode() + ": " + response.body());
            }
            return objectMapper.readValue(response.body(), new TypeReference<>() {});
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Diffusion verification request was interrupted", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("Diffusion verification API is unavailable at " + endpoint, exception);
        }
    }
}
