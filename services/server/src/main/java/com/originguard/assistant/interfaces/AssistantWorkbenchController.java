package com.originguard.assistant.interfaces;

import com.originguard.assistant.application.AssistantWorkbenchService;
import com.originguard.assistant.domain.AssistantConversation;
import com.originguard.identity.application.CurrentActorProvider;
import com.originguard.shared.application.RedisRateLimiter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/assistant/conversations")
public class AssistantWorkbenchController {
    private final AssistantWorkbenchService service;
    private final RedisRateLimiter rateLimiter;
    private final CurrentActorProvider actorProvider;

    public AssistantWorkbenchController(AssistantWorkbenchService service, RedisRateLimiter rateLimiter,
            CurrentActorProvider actorProvider) {
        this.service = service;
        this.rateLimiter = rateLimiter;
        this.actorProvider = actorProvider;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('agent:run')")
    public ResponseEntity<AssistantWorkbenchService.ConversationDetails> create(
            @Valid @RequestBody CreateConversationRequest request) {
        var created = service.createConversation(request.title());
        return ResponseEntity.created(URI.create("/api/v1/assistant/conversations/" + created.conversation().id()))
                .body(created);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('agent:run')")
    public List<AssistantConversation> list() {
        return service.listConversations();
    }

    @GetMapping("/{conversationId}")
    @PreAuthorize("hasAuthority('agent:run')")
    public AssistantWorkbenchService.ConversationDetails get(@PathVariable UUID conversationId) {
        return service.getConversation(conversationId);
    }

    @DeleteMapping("/{conversationId}")
    @PreAuthorize("hasAuthority('agent:run')")
    public ResponseEntity<Void> delete(@PathVariable UUID conversationId) {
        service.deleteConversation(conversationId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{conversationId}/messages")
    @PreAuthorize("hasAuthority('agent:run')")
    public AssistantWorkbenchService.ConversationDetails respond(
            @PathVariable UUID conversationId, @Valid @RequestBody SendMessageRequest request) {
        rateLimiter.requireAllowed(actorProvider.getRequiredActor().userId(), "assistant-message");
        List<UUID> assetIds = request.assetIds() == null || request.assetIds().isEmpty()
                ? request.assetId() == null ? List.of() : List.of(request.assetId())
                : request.assetIds();
        return service.respond(conversationId, request.content(), assetIds);
    }

    public record CreateConversationRequest(@Size(max = 160) String title) {}
    public record SendMessageRequest(
            @NotBlank @Size(max = 8000) String content,
            UUID assetId,
            @Size(max = 8) List<UUID> assetIds) {}
}
