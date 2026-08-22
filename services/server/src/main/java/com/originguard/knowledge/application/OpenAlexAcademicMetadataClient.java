package com.originguard.knowledge.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.originguard.knowledge.domain.ExternalKnowledgeCandidate;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class OpenAlexAcademicMetadataClient {
    private static final Map<String, Venue> VENUES = Map.ofEntries(
            Map.entry("CVPR", new Venue("CVPR", "IEEE/CVF Conference on Computer Vision and Pattern Recognition",
                    List.of("computer vision and pattern recognition"))),
            Map.entry("ICCV", new Venue("ICCV", "IEEE/CVF International Conference on Computer Vision",
                    List.of("international conference on computer vision"))),
            Map.entry("ECCV", new Venue("ECCV", "European Conference on Computer Vision",
                    List.of("european conference on computer vision", "eccv"))),
            Map.entry("ICLR", new Venue("ICLR", "International Conference on Learning Representations",
                    List.of("international conference on learning representations"))),
            Map.entry("NEURIPS", new Venue("NEURIPS", "Neural Information Processing Systems",
                    List.of("neural information processing systems", "neurips"))),
            Map.entry("ICML", new Venue("ICML", "International Conference on Machine Learning",
                    List.of("international conference on machine learning"))),
            Map.entry("TPAMI", new Venue("TPAMI", "IEEE Transactions on Pattern Analysis and Machine Intelligence",
                    List.of("transactions on pattern analysis and machine intelligence"))),
            Map.entry("TIFS", new Venue("TIFS", "IEEE Transactions on Information Forensics and Security",
                    List.of("transactions on information forensics and security"))),
            Map.entry("TMM", new Venue("TMM", "IEEE Transactions on Multimedia",
                    List.of("transactions on multimedia"))),
            Map.entry("IJCV", new Venue("IJCV", "International Journal of Computer Vision",
                    List.of("international journal of computer vision"))),
            Map.entry("PATTERN_RECOGNITION", new Venue("PATTERN_RECOGNITION", "Pattern Recognition",
                    List.of("pattern recognition"))),
            Map.entry("INFORMATION_FUSION", new Venue("INFORMATION_FUSION", "Information Fusion",
                    List.of("information fusion"))));

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient client;
    private final String baseUrl;
    private final String apiKey;
    private final Duration timeout;
    private final Map<String, List<String>> sourceIdsByVenue = new ConcurrentHashMap<>();

    public OpenAlexAcademicMetadataClient(
            @Value("${originguard.knowledge-expansion.openalex-base-url:https://api.openalex.org}") String baseUrl,
            @Value("${originguard.knowledge-expansion.openalex-api-key:}") String apiKey,
            @Value("${originguard.knowledge-expansion.timeout:PT20S}") Duration timeout) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.apiKey = apiKey.trim();
        this.timeout = timeout;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public List<ExternalKnowledgeCandidate> search(
            String query, List<String> venueCodes, int fromYear, int toYear, int limit) {
        Map<String, ExternalKnowledgeCandidate> candidates = new LinkedHashMap<>();
        for (String venueCode : venueCodes) {
            Venue venue = requireVenue(venueCode);
            for (ExternalKnowledgeCandidate candidate : searchVenue(query, venue, fromYear, toYear, limit)) {
                candidates.putIfAbsent(candidate.sourceIdentifier(), candidate);
            }
        }
        return candidates.values().stream()
                .sorted(Comparator.comparingInt(ExternalKnowledgeCandidate::publicationYear).reversed()
                        .thenComparing(Comparator.comparingInt(
                                ExternalKnowledgeCandidate::citedByCount).reversed()))
                .limit(limit)
                .toList();
    }

    public List<Map<String, String>> supportedVenues() {
        return VENUES.values().stream()
                .sorted(Comparator.comparing(Venue::code))
                .map(venue -> Map.of("code", venue.code(), "name", venue.name()))
                .toList();
    }

    private List<ExternalKnowledgeCandidate> searchVenue(
            String query, Venue venue, int fromYear, int toYear, int limit) {
        List<String> sourceIds = sourceIdsByVenue.computeIfAbsent(venue.code(), ignored -> resolveSourceIds(venue));
        String dateFilter = "from_publication_date:" + fromYear + "-01-01,to_publication_date:"
                + toYear + "-12-31,has_abstract:true";
        Map<String, ExternalKnowledgeCandidate> candidates = new LinkedHashMap<>();
        if (!sourceIds.isEmpty()) {
            fetchCandidates(
                    query,
                    dateFilter + ",locations.source.id:" + String.join("|", sourceIds),
                    venue,
                    Set.copyOf(sourceIds),
                    Math.min(100, Math.max(10, limit * 3)))
                    .forEach(candidate -> candidates.putIfAbsent(candidate.sourceIdentifier(), candidate));
        }
        // OpenAlex often leaves source.id empty for conference proceedings and only exposes
        // raw_source_name. Use a larger venue-keyword candidate pool as a compatibility fallback.
        fetchCandidates(query + " " + venue.code(), dateFilter, venue, Set.copyOf(sourceIds), 100)
                .forEach(candidate -> candidates.putIfAbsent(candidate.sourceIdentifier(), candidate));
        return candidates.values().stream().limit(limit).toList();
    }

    private List<ExternalKnowledgeCandidate> fetchCandidates(
            String query, String filter, Venue venue, Set<String> sourceIds, int perPage) {
        StringBuilder url = new StringBuilder(baseUrl).append("/works?search=")
                .append(encode(query))
                .append("&filter=").append(encode(filter))
                .append("&per_page=").append(perPage);
        if (!apiKey.isBlank()) url.append("&api_key=").append(encode(apiKey));
        try {
            HttpResponse<String> response = send(url.toString());
            requireSuccessful(response);
            JsonNode results = readPayload(response.body()).path("results");
            List<ExternalKnowledgeCandidate> candidates = new ArrayList<>();
            for (JsonNode work : results) {
                ExternalKnowledgeCandidate candidate = toCandidate(work, venue, sourceIds);
                if (candidate != null) candidates.add(candidate);
            }
            return candidates;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ExternalKnowledgeSourceUnavailableException(
                    "RAG_KNOWLEDGE_SOURCE_UNAVAILABLE", "OpenAlex 知识检索被中断", exception);
        } catch (IOException exception) {
            throw new ExternalKnowledgeSourceUnavailableException(
                    "RAG_KNOWLEDGE_SOURCE_UNAVAILABLE", "OpenAlex 知识来源暂不可用", exception);
        }
    }

    private List<String> resolveSourceIds(Venue venue) {
        StringBuilder url = new StringBuilder(baseUrl).append("/sources?search=")
                .append(encode(venue.name()))
                .append("&per_page=25");
        if (!apiKey.isBlank()) url.append("&api_key=").append(encode(apiKey));
        try {
            HttpResponse<String> response = send(url.toString());
            requireSuccessful(response);
            List<String> sourceIds = new ArrayList<>();
            for (JsonNode source : readPayload(response.body()).path("results")) {
                String id = source.path("id").asText().trim();
                String displayName = source.path("display_name").asText();
                if (!id.isBlank() && venue.matches(displayName)) sourceIds.add(shortOpenAlexId(id));
            }
            return sourceIds.stream().distinct().toList();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ExternalKnowledgeSourceUnavailableException(
                    "RAG_KNOWLEDGE_SOURCE_UNAVAILABLE", "OpenAlex 来源解析被中断", exception);
        } catch (IOException exception) {
            throw new ExternalKnowledgeSourceUnavailableException(
                    "RAG_KNOWLEDGE_SOURCE_UNAVAILABLE", "OpenAlex 来源解析暂不可用", exception);
        }
    }

    private ExternalKnowledgeCandidate toCandidate(
            JsonNode work, Venue expectedVenue, Set<String> expectedSourceIds) {
        JsonNode matchedLocation = findVenueLocation(work, expectedSourceIds, expectedVenue);
        if (matchedLocation == null) return null;
        String sourceName = locationSourceName(matchedLocation);
        if (sourceName.isBlank()) sourceName = expectedVenue.name();
        String identifier = work.path("id").asText();
        String title = work.path("title").asText().trim();
        String abstractText = reconstructAbstract(work.path("abstract_inverted_index"));
        int year = work.path("publication_year").asInt();
        if (identifier.isBlank() || title.isBlank() || abstractText.isBlank() || year == 0) return null;
        List<String> authors = new ArrayList<>();
        work.path("authorships").forEach(item -> {
            String name = item.path("author").path("display_name").asText().trim();
            if (!name.isBlank() && authors.size() < 20) authors.add(name);
        });
        String doi = work.path("doi").asText("");
        String sourceUrl = matchedLocation.path("landing_page_url").asText("");
        if (sourceUrl.isBlank()) {
            sourceUrl = work.path("primary_location").path("landing_page_url").asText("");
        }
        if (sourceUrl.isBlank()) sourceUrl = doi.isBlank() ? identifier : doi;
        return new ExternalKnowledgeCandidate(
                "OPENALEX", identifier, title, abstractText, List.copyOf(authors),
                expectedVenue.code(), sourceName, year, doi, sourceUrl,
                work.path("cited_by_count").asInt(0));
    }

    private JsonNode findVenueLocation(JsonNode work, Set<String> expectedSourceIds, Venue expectedVenue) {
        JsonNode primary = work.path("primary_location");
        if (matchesVenueLocation(primary, expectedSourceIds, expectedVenue)) {
            return primary;
        }
        for (JsonNode location : work.path("locations")) {
            if (matchesVenueLocation(location, expectedSourceIds, expectedVenue)) return location;
        }
        return null;
    }

    private boolean matchesVenueLocation(JsonNode location, Set<String> expectedSourceIds, Venue expectedVenue) {
        String sourceId = shortOpenAlexId(location.path("source").path("id").asText());
        if (!sourceId.isBlank() && expectedSourceIds.contains(sourceId)) return true;
        return expectedVenue.matches(locationSourceName(location));
    }

    private String locationSourceName(JsonNode location) {
        String displayName = location.path("source").path("display_name").asText().trim();
        return displayName.isBlank() ? location.path("raw_source_name").asText().trim() : displayName;
    }

    private HttpResponse<String> send(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("Accept", "application/json")
                .header("User-Agent", "OriginGuard-RAG-Knowledge-Expansion/1.0")
                .GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private void requireSuccessful(HttpResponse<String> response) {
        if (response.statusCode() == 429) {
            throw new ExternalKnowledgeSourceUnavailableException(
                    "RAG_KNOWLEDGE_SOURCE_RATE_LIMITED",
                    "OpenAlex 匿名检索当前限流，请稍后重试或配置免费的 OPENALEX_API_KEY");
        }
        if (response.statusCode() != 200) {
            throw new ExternalKnowledgeSourceUnavailableException(
                    "RAG_KNOWLEDGE_SOURCE_UNAVAILABLE",
                    "OpenAlex 知识来源暂不可用，HTTP " + response.statusCode());
        }
    }

    private JsonNode readPayload(String body) throws IOException {
        JsonNode payload = objectMapper.readTree(body);
        for (int depth = 0; depth < 2 && payload.isTextual(); depth++) {
            payload = objectMapper.readTree(payload.asText());
        }
        return payload;
    }

    private String shortOpenAlexId(String id) {
        int separator = id.lastIndexOf('/');
        return separator >= 0 ? id.substring(separator + 1) : id;
    }

    private String reconstructAbstract(JsonNode invertedIndex) {
        if (!invertedIndex.isObject()) return "";
        Map<Integer, String> words = new java.util.TreeMap<>();
        invertedIndex.fields().forEachRemaining(entry -> entry.getValue().forEach(
                position -> words.put(position.asInt(), entry.getKey())));
        return String.join(" ", words.values()).trim();
    }

    private Venue requireVenue(String code) {
        Venue venue = VENUES.get(code.toUpperCase(Locale.ROOT));
        if (venue == null) throw new IllegalArgumentException("Unsupported academic venue: " + code);
        return venue;
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private record Venue(String code, String name, List<String> aliases) {
        boolean matches(String sourceName) {
            String normalized = sourceName == null ? "" : sourceName.toLowerCase(Locale.ROOT);
            return aliases.stream().anyMatch(normalized::contains);
        }
    }
}
