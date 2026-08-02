package com.originguard.identity.domain;

import java.util.Set;
import java.util.UUID;

public record CurrentActor(
        UUID userId,
        UUID tenantId,
        String tenantCode,
        String username,
        String displayName,
        Set<String> roles,
        Set<String> permissions) {

    public CurrentActor {
        roles = Set.copyOf(roles);
        permissions = Set.copyOf(permissions);
    }

    public boolean hasPermission(String permission) {
        return permissions.contains(permission);
    }
}
