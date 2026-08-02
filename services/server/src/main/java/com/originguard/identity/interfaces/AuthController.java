package com.originguard.identity.interfaces;

import com.originguard.identity.application.AuthenticationService;
import com.originguard.identity.application.CurrentActorProvider;
import com.originguard.identity.domain.CurrentActor;
import com.originguard.identity.domain.UserAccount;
import com.originguard.identity.infrastructure.JwtProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    static final String REFRESH_COOKIE = "og_refresh_token";

    private final AuthenticationService authenticationService;
    private final CurrentActorProvider actorProvider;
    private final JwtProperties jwtProperties;

    public AuthController(
            AuthenticationService authenticationService,
            CurrentActorProvider actorProvider,
            JwtProperties jwtProperties) {
        this.authenticationService = authenticationService;
        this.actorProvider = actorProvider;
        this.jwtProperties = jwtProperties;
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        var session = authenticationService.login(request.tenantCode(), request.username(), request.password());
        return response(session);
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new com.originguard.identity.application.InvalidRefreshTokenException();
        }
        return response(authenticationService.refresh(refreshToken));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken) {
        authenticationService.logout(refreshToken);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, clearCookie().toString())
                .build();
    }

    @GetMapping("/me")
    public UserView me() {
        CurrentActor actor = actorProvider.getRequiredActor();
        return new UserView(
                actor.userId(),
                actor.tenantId(),
                actor.tenantCode(),
                actor.username(),
                actor.displayName(),
                actor.roles(),
                actor.permissions());
    }

    private ResponseEntity<AuthResponse> response(AuthenticationService.AuthenticatedSession session) {
        var access = session.accessToken();
        AuthResponse body = new AuthResponse(
                access.value(),
                "Bearer",
                Duration.between(access.issuedAt(), access.expiresAt()).toSeconds(),
                UserView.from(session.user()));
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookie(session.refreshToken()).toString())
                .body(body);
    }

    private ResponseCookie refreshCookie(String token) {
        return ResponseCookie.from(REFRESH_COOKIE, token)
                .httpOnly(true)
                .secure(jwtProperties.refreshCookieSecure())
                .sameSite("Strict")
                .path("/api/v1/auth")
                .maxAge(jwtProperties.refreshTokenTtl())
                .build();
    }

    private ResponseCookie clearCookie() {
        return ResponseCookie.from(REFRESH_COOKIE, "")
                .httpOnly(true)
                .secure(jwtProperties.refreshCookieSecure())
                .sameSite("Strict")
                .path("/api/v1/auth")
                .maxAge(Duration.ZERO)
                .build();
    }

    public record LoginRequest(
            @NotBlank @Pattern(regexp = "[a-zA-Z0-9_-]{2,64}") String tenantCode,
            @NotBlank String username,
            @NotBlank String password) {}

    public record AuthResponse(String accessToken, String tokenType, long expiresIn, UserView user) {}

    public record UserView(
            UUID id,
            UUID tenantId,
            String tenantCode,
            String username,
            String displayName,
            Set<String> roles,
            Set<String> permissions) {
        static UserView from(UserAccount user) {
            return new UserView(
                    user.id(),
                    user.tenantId(),
                    user.tenantCode(),
                    user.username(),
                    user.displayName(),
                    user.roles(),
                    user.permissions());
        }
    }
}
