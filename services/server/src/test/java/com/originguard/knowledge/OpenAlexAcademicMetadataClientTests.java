package com.originguard.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.originguard.knowledge.application.OpenAlexAcademicMetadataClient;
import com.originguard.knowledge.domain.ExternalKnowledgeCandidate;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

class OpenAlexAcademicMetadataClientTests {
    @Test
    void reconstructsAbstractAndKeepsOnlySelectedAuthoritativeVenue() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> worksQueries = new CopyOnWriteArrayList<>();
        server.createContext("/sources", exchange -> {
            byte[] response = """
                    {"results":[{
                      "id":"https://openalex.org/S111",
                      "display_name":"Proceedings of the IEEE/CVF Conference on Computer Vision and Pattern Recognition"
                    }]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/works", exchange -> {
            worksQueries.add(URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8));
            String payload = """
                    {
                      "results": [
                        {
                          "id": "https://openalex.org/W123456",
                          "title": "Generalizable AI-generated Image Detection",
                          "publication_year": 2025,
                          "doi": "https://doi.org/10.1000/test",
                          "cited_by_count": 12,
                          "primary_location": {
                            "landing_page_url": "https://openaccess.thecvf.com/test",
                            "source": null,
                            "raw_source_name":"2025 IEEE/CVF Conference on Computer Vision and Pattern Recognition (CVPR)"
                          },
                          "locations": [],
                          "authorships": [{"author": {"display_name": "Test Author"}}],
                          "abstract_inverted_index": {
                            "AIGC": [0], "detection": [1], "remains": [2], "challenging": [3]
                          }
                        },
                        {
                          "id": "https://openalex.org/W999999",
                          "title": "Unrelated Journal Work",
                          "publication_year": 2025,
                          "primary_location": {
                            "landing_page_url": "https://example.org/work",
                            "source": {"id":"https://openalex.org/S999","display_name": "Unrelated Journal"}
                          },
                          "locations": [],
                          "authorships": [],
                          "abstract_inverted_index": {"Not": [0], "selected": [1]}
                        }
                      ]
                    }
                    """;
            byte[] response = new ObjectMapper().writeValueAsBytes(payload);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            OpenAlexAcademicMetadataClient client = new OpenAlexAcademicMetadataClient(
                    "http://127.0.0.1:" + server.getAddress().getPort(), "", Duration.ofSeconds(5));

            List<ExternalKnowledgeCandidate> results = client.search(
                    "AI", List.of("CVPR"), 2023, 2026, 10);

            assertThat(results).hasSize(1);
            assertThat(results.getFirst().abstractText()).isEqualTo("AIGC detection remains challenging");
            assertThat(results.getFirst().venueCode()).isEqualTo("CVPR");
            assertThat(results.getFirst().authors()).containsExactly("Test Author");
            assertThat(results.getFirst().sourceUrl()).isEqualTo("https://openaccess.thecvf.com/test");
            assertThat(worksQueries).hasSize(2);
            assertThat(worksQueries.getFirst()).contains("search=AI");
            assertThat(worksQueries.getFirst()).contains("locations.source.id:S111");
            assertThat(worksQueries.getLast()).contains("search=AI CVPR");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void exposesOnlyMaintainedVenueWhitelist() {
        OpenAlexAcademicMetadataClient client = new OpenAlexAcademicMetadataClient(
                "https://api.openalex.org", "", Duration.ofSeconds(1));

        assertThat(client.supportedVenues())
                .extracting(item -> item.get("code"))
                .contains("CVPR", "ICCV", "ECCV", "TIFS", "TPAMI");
    }
}
