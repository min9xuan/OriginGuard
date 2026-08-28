package com.originguard.identity.application;

import com.originguard.identity.domain.CurrentActor;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

@Component
public class CurrentActorProvider {
    private final ThreadLocal<CurrentActor> workerActor = new ThreadLocal<>();

    public CurrentActor getRequiredActor() {
        CurrentActor delegated = workerActor.get();
        if (delegated != null) return delegated;
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

    public <T> T runAs(CurrentActor actor, Supplier<T> action) {
        CurrentActor previous = workerActor.get();
        workerActor.set(actor);
        try {
            return action.get();
        } finally {
            if (previous == null) workerActor.remove();
            else workerActor.set(previous);
        }
    }

    private Set<String> claimSet(Jwt jwt, String claimName) {
        List<String> values = jwt.getClaimAsStringList(claimName);
        return values == null ? Set.of() : new HashSet<>(values);
    }
}
