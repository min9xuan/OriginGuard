package com.originguard.identity.application;

import com.originguard.identity.domain.CurrentActor;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

@Component
public class CurrentActorProvider {

    public CurrentActor getRequiredActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            throw new IllegalStateException("An authenticated JWT principal is required");
        }

        return new CurrentActor(
                UUID.fromString(jwt.getSubject()),
                UUID.fromString(jwt.getClaimAsString("tenantId")),
                jwt.getClaimAsString("tenantCode"),
                jwt.getClaimAsString("username"),
                jwt.getClaimAsString("displayName"),
                claimSet(jwt, "roles"),
                claimSet(jwt, "permissions"));
    }

    private Set<String> claimSet(Jwt jwt, String claimName) {
        List<String> values = jwt.getClaimAsStringList(claimName);
        return values == null ? Set.of() : new HashSet<>(values);
    }
}
