package com.originguard.identity.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "originguard.bootstrap")
public record BootstrapProperties(boolean enabled, String tenantCode, String tenantName, String password) {}

