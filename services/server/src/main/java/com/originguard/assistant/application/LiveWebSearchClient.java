package com.originguard.assistant.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class LiveWebSearchClient {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final String provider;
    private final String tavilyApiKey;
    private final String tavilyBaseUrl;
    private final String openAlexBaseUrl;
    private final String openAlexApiKey;
    private final Duration timeout;

    public LiveWebSearchClient(
            @Value("${originguard.assistant.web-search.provider:auto}") String provider,
            @Value("${originguard.assistant.web-search.tavily-api-key:}") String tavilyApiKey,
            @Value("${originguard.assistant.web-search.tavily-base-url:https://api.tavily.com}") String tavilyBaseUrl,
            @Value("${originguard.knowledge-expansion.openalex-base-url:https://api.openalex.org}") String openAlexBaseUrl,
            @Value("${originguard.knowledge-expansion.openalex-api-key:}") String openAlexApiKey,
            @Value("${originguard.assistant.web-search.timeout:PT20S}") Duration timeout) {
        this.provider = provider.trim().toLowerCase();
        this.tavilyApiKey = tavilyApiKey.trim();
        this.tavilyBaseUrl = tavilyBaseUrl.replaceAll("/+$", "");
        this.openAlexBaseUrl = openAlexBaseUrl.replaceAll("/+$", "");
        this.openAlexApiKey = openAlexApiKey.trim();
        this.timeout = timeout;
    }

    public SearchResponse search(String query, int limit) {
        if ("disabled".equals(provider)) return new SearchResponse("DISABLED", List.of());
        if (("auto".equals(provider) || "tavily".equals(provider)) && !tavilyApiKey.isBlank()) {
            return new SearchResponse("TAVILY", searchTavily(query, limit));
        }
        if ("tavily".equals(provider)) return new SearchResponse("TAVILY_NOT_CONFIGURED", List.of());
        return new SearchResponse("OPENALEX_LIVE", searchOpenAlex(query, limit));
    }

    public SearchResponse searchAcademic(String query, int limit) {
        if ("disabled".equals(provider)) return new SearchResponse("DISABLED", List.of());
        return new SearchResponse("OPENALEX_ACADEMIC", searchOpenAlex(query, limit));
    }

    private List<WebSource> searchTavily(String query, int limit) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("api_key", tavilyApiKey);
            body.put("query", query);
            body.put("search_depth", "advanced");
            body.put("max_results", limit);
            body.put("include_answer", false);
            HttpRequest request = HttpRequest.newBuilder(URI.create(tavilyBaseUrl + "/search"))
                    .timeout(timeout).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body))).build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return List.of();
            List<WebSource> results = new ArrayList<>();
            for (JsonNode item : objectMapper.readTree(response.body()).path("results")) {
                String url = item.path("url").asText("").trim();
                if (!url.startsWith("https://") || results.size() >= limit) continue;
                results.add(new WebSource(
                        "TAVILY", item.path("title").asText("网页来源"), url,
                        compact(item.path("content").asText("")), item.path("score").asDouble(0),
                        "", null, "GENERAL_WEB", "普通网页来源，适合一般问题，不作为专业检测结论依据"));
            }
            return List.copyOf(results);
        } catch (IOException exception) {
            return List.of();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return List.of();
        }
    }

    private List<WebSource> searchOpenAlex(String query, int limit) {
        StringBuilder url = new StringBuilder(openAlexBaseUrl).append("/works?search=")
                .append(encode(query)).append("&filter=has_abstract:true&sort=relevance_score:desc&per_page=")
                .append(Math.min(10, Math.max(1, limit)));
        if (!openAlexApiKey.isBlank()) url.append("&api_key=").append(encode(openAlexApiKey));
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url.toString()))
                    .timeout(timeout).header("Accept", "application/json")
                    .header("User-Agent", "OriginGuard-Assistant-Live-RAG/1.0").GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return List.of();
            List<WebSource> results = new ArrayList<>();
            for (JsonNode work : objectMapper.readTree(response.body()).path("results")) {
                String target = work.path("primary_location").path("landing_page_url").asText("").trim();
                if (target.isBlank()) target = work.path("doi").asText("").trim();
                if (target.isBlank()) target = work.path("id").asText("").trim();
                String abstractText = reconstructAbstract(work.path("abstract_inverted_index"));
                if (!target.startsWith("https://") || abstractText.isBlank()) continue;
                String venue = work.path("primary_location").path("source").path("display_name").asText("").trim();
                Quality quality = academicQuality(venue, work.path("type").asText(""));
                results.add(new WebSource(
                        "OPENALEX", work.path("title").asText("学术来源"), target,
                        compact(abstractText), work.path("relevance_score").asDouble(0), venue,
                        work.path("publication_year").isInt() ? work.path("publication_year").asInt() : null,
                        quality.tier(), quality.reason()));
            }
            return List.copyOf(results);
        } catch (IOException exception) {
            return List.of();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return List.of();
        }
    }

    private String reconstructAbstract(JsonNode invertedIndex) {
        if (!invertedIndex.isObject()) return "";
        Map<Integer, String> words = new java.util.TreeMap<>();
        invertedIndex.fields().forEachRemaining(entry -> entry.getValue().forEach(
                position -> words.put(position.asInt(), entry.getKey())));
        return String.join(" ", words.values()).trim();
    }

    private String compact(String text) {
        String normalized = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 1200 ? normalized : normalized.substring(0, 1200) + "…";
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private Quality academicQuality(String venue, String workType) {
        String normalized = venue == null ? "" : venue.toLowerCase();
        if (containsAny(normalized,
                "transactions on pattern analysis and machine intelligence",
                "transactions on information forensics and security",
                "transactions on image processing", "international journal of computer vision",
                "pattern recognition", "computer vision and pattern recognition",
                "international conference on computer vision", "neurips", "neural information processing systems",
                "international conference on machine learning", "aaai conference on artificial intelligence",
                "acm multimedia", "siggraph")) {
            return new Quality("CURATED_TOP_VENUE", "命中 OriginGuard 维护的计算机视觉/AI 顶级期刊或会议名单");
        }
        if (normalized.contains("ieee") && (normalized.contains("transactions") || normalized.contains("journal"))) {
            return new Quality("IEEE_JOURNAL", "IEEE 期刊来源；分区仍需按发表年份和采用的分区体系复核");
        }
        if ("journal-article".equalsIgnoreCase(workType) || "article".equalsIgnoreCase(workType)) {
            return new Quality("ACADEMIC_JOURNAL", "学术期刊来源，未在本地顶级来源名单中确认分区");
        }
        return new Quality("ACADEMIC_OTHER", "学术索引来源，会议等级或期刊分区尚未核验");
    }

    private boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) if (value.contains(candidate)) return true;
        return false;
    }

    public record SearchResponse(String provider, List<WebSource> sources) {
        public SearchResponse { sources = List.copyOf(sources); }
    }

    public record WebSource(
            String provider, String title, String url, String snippet, double score,
            String venue, Integer publicationYear, String qualityTier, String qualityReason) {}
    private record Quality(String tier, String reason) {}
}
