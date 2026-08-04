package com.originguard.audit.application;

import com.originguard.audit.domain.AuditEntry;
import com.originguard.audit.infrastructure.AuditLogRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class AuditService {
    private final AuditLogRepository repository;

    public AuditService(AuditLogRepository repository) {
        this.repository = repository;
    }

    public void record(
            UUID tenantId,
            UUID actorUserId,
            String action,
            String resourceType,
            UUID resourceId,
            Map<String, ?> details) {
        repository.append(tenantId, actorUserId, action, resourceType, resourceId, details);
    }

    public List<AuditEntry> history(UUID tenantId, String resourceType, UUID resourceId) {
        return repository.findForResource(tenantId, resourceType, resourceId);
    }
}
