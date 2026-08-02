package com.originguard.identity.interfaces;

import com.originguard.identity.application.CurrentActorProvider;
import com.originguard.identity.infrastructure.IdentityRepository;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/identity")
public class AdminIdentityController {
    private final CurrentActorProvider actorProvider;
    private final IdentityRepository identityRepository;

    public AdminIdentityController(CurrentActorProvider actorProvider, IdentityRepository identityRepository) {
        this.actorProvider = actorProvider;
        this.identityRepository = identityRepository;
    }

    @GetMapping("/summary")
    @PreAuthorize("hasAuthority('user:manage')")
    public TenantIdentitySummary summary() {
        var actor = actorProvider.getRequiredActor();
        var counts = identityRepository.countForTenant(actor.tenantId());
        return new TenantIdentitySummary(actor.tenantId(), actor.tenantCode(), counts.users(), counts.enabledUsers());
    }

    public record TenantIdentitySummary(UUID tenantId, String tenantCode, int users, int enabledUsers) {}
}

