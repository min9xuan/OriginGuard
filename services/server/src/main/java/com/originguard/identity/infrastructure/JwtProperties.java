package com.originguard.identity.infrastructure;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "originguard.security.jwt")
public record JwtProperties(
        String issuer,
        String secret,
        Duration accessTokenTtl,
        Duration refreshTokenTtl,
        boolean refreshCookieSecure) {}

