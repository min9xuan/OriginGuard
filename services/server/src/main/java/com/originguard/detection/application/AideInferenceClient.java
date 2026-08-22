package com.originguard.detection.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AideInferenceClient {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient client;
    private final URI endpoint;
    private final Duration timeout;

    public AideInferenceClient(
            @Value("${originguard.agent.aigc-detector.base-url:http://127.0.0.1:8090}") String baseUrl,
            @Value("${originguard.agent.aigc-detector.timeout:PT10M}") Duration timeout) {
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        this.endpoint = URI.create(baseUrl.replaceAll("/+$", "") + "/v1/aigc/detect");
        this.timeout = timeout;
    }

    public Inference detect(byte[] content, String contentType) {
        try {
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(timeout)
                    .header("Content-Type", contentType)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(content))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException(
                        "AIDE model API returned HTTP " + response.statusCode() + ": " + response.body());
            }
            Map<String, Object> payload = objectMapper.readValue(response.body(), new TypeReference<>() {});
            Map<?, ?> quality = payload.get("qualityAssessment") instanceof Map<?, ?> value ? value : Map.of();
            Object qualityStatus = quality.containsKey("status") ? quality.get("status") : "UNKNOWN";
            return new Inference(
                    number(payload.get("syntheticProbability")),
                    String.valueOf(payload.getOrDefault("model", "AIDE")),
                    String.valueOf(payload.getOrDefault("modelVersion", "unknown")),
                    ((Number) payload.getOrDefault("processingMilliseconds", 0)).longValue(),
                    String.valueOf(qualityStatus));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("AIDE evaluation request was interrupted", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("AIDE model API is unavailable at " + endpoint, exception);
        }
    }

    private double number(Object value) {
        if (!(value instanceof Number number)) {
            throw new IllegalStateException("AIDE response did not contain syntheticProbability");
        }
        double result = number.doubleValue();
        if (result < 0 || result > 1) throw new IllegalStateException("Invalid AIDE probability: " + result);
        return result;
    }

    public record Inference(
            double syntheticProbability,
            String modelCode,
            String modelVersion,
            long processingMilliseconds,
            String qualityStatus) {}
}
