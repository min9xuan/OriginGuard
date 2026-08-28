package com.originguard.agent.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class C2paVerificationClient {
    private final boolean enabled;
    private final URI endpoint;
    private final Duration timeout;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient client;

    public C2paVerificationClient(
            @Value("${originguard.agent.c2pa.enabled:false}") boolean enabled,
            @Value("${originguard.agent.c2pa.base-url:http://127.0.0.1:8091}") String baseUrl,
            @Value("${originguard.agent.c2pa.timeout:PT45S}") Duration timeout) {
        this.enabled = enabled;
        this.endpoint = URI.create(baseUrl + "/v1/verify");
        this.timeout = timeout;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    public Map<String, Object> verify(byte[] content, String filename, String contentType) {
        if (!enabled) return unavailable("NOT_CONFIGURED", "C2PA verification is disabled");
        try {
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(timeout)
                    .header("Content-Type", contentType)
                    .header("X-Filename", safeHeader(filename))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(content))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return unavailable("UNAVAILABLE", "C2PA sidecar returned HTTP " + response.statusCode());
            }
            Map<String, Object> result = objectMapper.readValue(
                    response.body(), new TypeReference<Map<String, Object>>() {});
            return Map.copyOf(result);
        } catch (Exception exception) {
            return unavailable("UNAVAILABLE", "C2PA verification unavailable: " + safeMessage(exception));
        }
    }

    private Map<String, Object> unavailable(String status, String reason) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", status);
        result.put("credentialPresent", false);
        result.put("manifestCount", 0);
        result.put("activeManifest", "");
        result.put("signer", "");
        result.put("actions", java.util.List.of());
        result.put("validationStatus", java.util.List.of());
        result.put("limitations", java.util.List.of(reason));
        return Map.copyOf(result);
    }

    private String safeHeader(String value) {
        return value == null ? "asset.bin" : value.replaceAll("[\\r\\n]", "");
    }

    private String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }
}
