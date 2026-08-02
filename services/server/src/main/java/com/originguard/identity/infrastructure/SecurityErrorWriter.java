package com.originguard.identity.infrastructure;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

@Component
public class SecurityErrorWriter implements AuthenticationEntryPoint, AccessDeniedHandler {
    @Override
    public void commence(
            HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
            throws IOException {
        write(response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHORIZED", "Authentication is required");
    }

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            org.springframework.security.access.AccessDeniedException exception)
            throws IOException {
        write(response, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN", "Permission denied");
    }

    private void write(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }
}

