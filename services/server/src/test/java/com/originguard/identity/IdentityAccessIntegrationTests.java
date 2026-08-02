package com.originguard.identity;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Testcontainers
class IdentityAccessIntegrationTests {
    private static final String PASSWORD = "OriginGuard@123";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcClient jdbcClient;

    @Test
    void unauthenticatedRequestReturnsStructured401() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void invalidPasswordDoesNotRevealWhichCredentialFailed() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson("investigator", "wrong-password")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_FAILED"));
    }

    @Test
    void investigatorCanLoginButCannotUseAdminPermission() throws Exception {
        MvcResult login = login("investigator");
        String accessToken = accessToken(login);

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantCode").value("demo"))
                .andExpect(jsonPath("$.roles", hasItem("INVESTIGATOR")))
                .andExpect(jsonPath("$.permissions", hasItem("case:create")));

        mockMvc.perform(get("/api/v1/admin/identity/summary")
                        .header("Authorization", bearer(accessToken)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void administratorIsTenantScopedAndCannotFinalizeReports() throws Exception {
        UUID anotherTenant = UUID.randomUUID();
        jdbcClient.sql("INSERT INTO tenant(id, code, name) VALUES (:id, 'other', 'Other Tenant')")
                .param("id", anotherTenant)
                .update();
        jdbcClient.sql("""
                        INSERT INTO sys_user(tenant_id, username, display_name, password_hash)
                        VALUES (:tenantId, 'outsider', 'Other User', 'not-used')
                        """)
                .param("tenantId", anotherTenant)
                .update();

        MvcResult login = login("admin");
        String accessToken = accessToken(login);
        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles", hasItem("ADMIN")))
                .andExpect(jsonPath("$.permissions", hasItem("user:manage")))
                .andExpect(jsonPath("$.permissions", not(hasItem("review:approve"))))
                .andExpect(jsonPath("$.permissions", not(hasItem("report:finalize"))));

        mockMvc.perform(get("/api/v1/admin/identity/summary")
                        .header("Authorization", bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantCode").value("demo"))
                .andExpect(jsonPath("$.users").value(3));
    }

    @Test
    void reviewerReceivesReviewAndFinalizePermissions() throws Exception {
        String accessToken = accessToken(login("reviewer"));
        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles", hasItem("REVIEWER")))
                .andExpect(jsonPath("$.permissions", hasItem("review:approve")))
                .andExpect(jsonPath("$.permissions", hasItem("report:finalize")));
    }

    @Test
    void refreshTokenRotatesAndLogoutRevokesReplacement() throws Exception {
        MvcResult login = login("investigator");
        Cookie originalCookie = login.getResponse().getCookie("og_refresh_token");

        MvcResult refreshed = mockMvc.perform(post("/api/v1/auth/refresh").cookie(originalCookie))
                .andExpect(status().isOk())
                .andExpect(cookie().httpOnly("og_refresh_token", true))
                .andReturn();
        Cookie replacementCookie = refreshed.getResponse().getCookie("og_refresh_token");

        mockMvc.perform(post("/api/v1/auth/refresh").cookie(originalCookie))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/auth/logout")
                        .cookie(replacementCookie)
                        .header("Authorization", bearer(accessToken(refreshed))))
                .andExpect(status().isNoContent())
                .andExpect(cookie().maxAge("og_refresh_token", 0));

        mockMvc.perform(post("/api/v1/auth/refresh").cookie(replacementCookie))
                .andExpect(status().isUnauthorized());
    }

    private MvcResult login(String username) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(username, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(cookie().httpOnly("og_refresh_token", true))
                .andReturn();
    }

    private String accessToken(MvcResult result) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    }

    private String loginJson(String username, String password) {
        return """
                {"tenantCode":"demo","username":"%s","password":"%s"}
                """.formatted(username, password);
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
