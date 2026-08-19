package com.originguard.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.originguard.knowledge.application.OpenAlexAcademicMetadataClient;
import com.originguard.knowledge.domain.ExternalKnowledgeCandidate;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class OpenAlexAcademicMetadataClientTests {
    @Test
    void reconstructsAbstractAndKeepsOnlySelectedAuthoritativeVenue() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/works", exchange -> {
            byte[] response = """
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
                            "source": {"display_name": "2025 IEEE/CVF Conference on Computer Vision and Pattern Recognition (CVPR)"}
                          },
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
                            "source": {"display_name": "Unrelated Journal"}
                          },
                          "authorships": [],
                          "abstract_inverted_index": {"Not": [0], "selected": [1]}
                        }
                      ]
                    }
                    """.getBytes(StandardCharsets.UTF_8);
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
                    "AI-generated image detection", List.of("CVPR"), 2023, 2026, 10);

            assertThat(results).hasSize(1);
            assertThat(results.getFirst().abstractText()).isEqualTo("AIGC detection remains challenging");
            assertThat(results.getFirst().venueCode()).isEqualTo("CVPR");
            assertThat(results.getFirst().authors()).containsExactly("Test Author");
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
