package com.originguard.identity.domain;

import java.util.Set;
import java.util.UUID;

public record UserAccount(
        UUID id,
        UUID tenantId,
        String tenantCode,
        String username,
        String displayName,
        String passwordHash,
        boolean enabled,
        int tokenVersion,
        Set<String> roles,
        Set<String> permissions) {

    public UserAccount {
        roles = Set.copyOf(roles);
        permissions = Set.copyOf(permissions);
    }
}

