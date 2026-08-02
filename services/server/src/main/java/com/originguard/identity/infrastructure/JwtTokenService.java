package com.originguard.identity.infrastructure;

import com.originguard.identity.domain.UserAccount;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

@Component
public class JwtTokenService {
    private final JwtEncoder encoder;
    private final JwtProperties properties;
    private final Clock clock;

    public JwtTokenService(JwtEncoder encoder, JwtProperties properties, Clock clock) {
        this.encoder = encoder;
        this.properties = properties;
        this.clock = clock;
    }

    public IssuedAccessToken issue(UserAccount user) {
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plus(properties.accessTokenTtl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .subject(user.id().toString())
                .claim("tenantId", user.tenantId().toString())
                .claim("tenantCode", user.tenantCode())
                .claim("username", user.username())
                .claim("displayName", user.displayName())
                .claim("tokenVersion", user.tokenVersion())
                .claim("roles", List.copyOf(user.roles()))
                .claim("permissions", List.copyOf(user.permissions()))
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String value = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedAccessToken(value, issuedAt, expiresAt);
    }

    public record IssuedAccessToken(String value, Instant issuedAt, Instant expiresAt) {}
}

