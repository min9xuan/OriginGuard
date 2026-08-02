package com.originguard.identity.application;

import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

@Component
public class TenantAccessPolicy {
    private final CurrentActorProvider actorProvider;

    public TenantAccessPolicy(CurrentActorProvider actorProvider) {
        this.actorProvider = actorProvider;
    }

    public void requireCurrentTenant(UUID resourceTenantId) {
        if (!actorProvider.getRequiredActor().tenantId().equals(resourceTenantId)) {
            throw new AccessDeniedException("Resource belongs to another tenant");
        }
    }
}

