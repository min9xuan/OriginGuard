package com.originguard.identity.application;

import com.originguard.identity.domain.UserAccount;
import com.originguard.identity.infrastructure.IdentityRepository;
import com.originguard.identity.infrastructure.JwtProperties;
import com.originguard.identity.infrastructure.JwtTokenService;
import com.originguard.identity.infrastructure.RefreshTokenRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthenticationService {
    private final IdentityRepository identityRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtTokenService jwtTokenService;
    private final JwtProperties jwtProperties;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();

    public AuthenticationService(
            IdentityRepository identityRepository,
            RefreshTokenRepository refreshTokenRepository,
            JwtTokenService jwtTokenService,
            JwtProperties jwtProperties,
            PasswordEncoder passwordEncoder,
            Clock clock) {
        this.identityRepository = identityRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.jwtTokenService = jwtTokenService;
        this.jwtProperties = jwtProperties;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    @Transactional
    public AuthenticatedSession login(String tenantCode, String username, String password) {
        UserAccount user = identityRepository
                .findByTenantAndUsername(tenantCode.trim(), username.trim())
                .filter(UserAccount::enabled)
                .filter(found -> passwordEncoder.matches(password, found.passwordHash()))
                .orElseThrow(InvalidCredentialsException::new);
        return issueSession(user);
    }

    @Transactional
    public AuthenticatedSession refresh(String rawRefreshToken) {
        Instant now = clock.instant();
        var stored = refreshTokenRepository
                .findActive(hash(rawRefreshToken), now)
                .orElseThrow(InvalidRefreshTokenException::new);
        UserAccount user = identityRepository
                .findById(stored.userId())
                .filter(UserAccount::enabled)
                .orElseThrow(InvalidRefreshTokenException::new);

        UUID replacementId = UUID.randomUUID();
        String replacementValue = randomToken();
        refreshTokenRepository.save(
                replacementId,
                user.id(),
                hash(replacementValue),
                now.plus(jwtProperties.refreshTokenTtl()));
        refreshTokenRepository.revoke(stored.id(), now, replacementId);
        return session(user, replacementValue);
    }

    @Transactional
    public void logout(String rawRefreshToken) {
        if (rawRefreshToken != null && !rawRefreshToken.isBlank()) {
            refreshTokenRepository.revokeByHash(hash(rawRefreshToken), clock.instant());
        }
    }

    private AuthenticatedSession issueSession(UserAccount user) {
        String refreshToken = randomToken();
        refreshTokenRepository.save(
                UUID.randomUUID(),
                user.id(),
                hash(refreshToken),
                clock.instant().plus(jwtProperties.refreshTokenTtl()));
        return session(user, refreshToken);
    }

    private AuthenticatedSession session(UserAccount user, String refreshToken) {
        return new AuthenticatedSession(user, jwtTokenService.issue(user), refreshToken);
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    public record AuthenticatedSession(
            UserAccount user, JwtTokenService.IssuedAccessToken accessToken, String refreshToken) {}
}

