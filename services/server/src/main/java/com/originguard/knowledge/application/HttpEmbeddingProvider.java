package com.originguard.knowledge.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "originguard.embedding.provider",
        havingValue = "bge-small-zh-v1.5",
        matchIfMissing = true)
public class HttpEmbeddingProvider implements EmbeddingProvider {
    public static final String PROVIDER = "LOCAL_BGE_SMALL_ZH_V1_5";
    public static final int DIMENSIONS = 512;
    private final ObjectMapper objectMapper;
    private final HttpClient client;
    private final URI endpoint;
    private final Duration timeout;

    public HttpEmbeddingProvider(
            @Value("${originguard.embedding.model-api-base-url:http://localhost:8090}") String baseUrl,
            @Value("${originguard.embedding.timeout:PT30S}") Duration timeout) {
        this.objectMapper = new ObjectMapper();
        this.timeout = timeout;
        this.client = HttpClient.newBuilder().connectTimeout(timeout).build();
        this.endpoint = URI.create(baseUrl.replaceAll("/+$", "") + "/v1/embeddings");
    }

    @Override
    public String code() { return PROVIDER; }

    @Override
    public int dimensions() { return DIMENSIONS; }

    @Override
    public String embedAsVector(String text) {
        try {
            String body = objectMapper.writeValueAsString(Map.of("inputs", List.of(text)));
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException(
                        "Embedding model API returned HTTP " + response.statusCode() + ": " + response.body());
            }
            JsonNode payload = objectMapper.readTree(response.body());
            if (!PROVIDER.equals(payload.path("provider").asText())
                    || payload.path("dimensions").asInt() != DIMENSIONS) {
                throw new IllegalStateException("Embedding model API returned an incompatible provider");
            }
            JsonNode values = payload.path("embeddings").path(0);
            if (!values.isArray() || values.size() != DIMENSIONS) {
                throw new IllegalStateException("Embedding model API returned an invalid vector");
            }
            StringBuilder vector = new StringBuilder("[");
            for (int index = 0; index < values.size(); index++) {
                if (index > 0) vector.append(',');
                vector.append(String.format(Locale.ROOT, "%.8f", values.get(index).asDouble()));
            }
            return vector.append(']').toString();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Embedding model API request was interrupted", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("Embedding model API is unavailable at " + endpoint, exception);
        }
    }
}
