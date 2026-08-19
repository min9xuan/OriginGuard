package com.originguard.knowledge.application;

import com.originguard.audit.application.AuditService;
import com.originguard.identity.application.CurrentActorProvider;
import com.originguard.identity.domain.CurrentActor;
import com.originguard.knowledge.domain.ExternalKnowledgeCandidate;
import com.originguard.knowledge.domain.KnowledgeDocument;
import com.originguard.knowledge.infrastructure.KnowledgeRepository;
import com.originguard.shared.application.BusinessConflictException;
import java.net.URI;
import java.time.Year;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RagKnowledgeExpansionService {
    private final OpenAlexAcademicMetadataClient metadataClient;
    private final KnowledgeRepository repository;
    private final CurrentActorProvider actorProvider;
    private final AuditService auditService;

    public RagKnowledgeExpansionService(
            OpenAlexAcademicMetadataClient metadataClient,
            KnowledgeRepository repository,
            CurrentActorProvider actorProvider,
            AuditService auditService) {
        this.metadataClient = metadataClient;
        this.repository = repository;
        this.actorProvider = actorProvider;
        this.auditService = auditService;
    }

    public DiscoveryResult discover(String query, List<String> venueCodes, int fromYear, int toYear, int limit) {
        int currentYear = Year.now().getValue();
        if (fromYear < 2000 || toYear > currentYear + 1 || fromYear > toYear) {
            throw new IllegalArgumentException("Invalid publication year range");
        }
        List<String> venues = venueCodes.stream().distinct().toList();
        if (venues.isEmpty() || venues.size() > 6) {
            throw new IllegalArgumentException("Select between 1 and 6 academic venues");
        }
        List<ExternalKnowledgeCandidate> candidates = metadataClient.search(
                query.trim(), venues, fromYear, toYear, limit);
        return new DiscoveryResult(query.trim(), venues, fromYear, toYear, candidates);
    }

    public List<Map<String, String>> supportedVenues() {
        return metadataClient.supportedVenues();
    }

    @Transactional
    public KnowledgeDocument createDraft(ExternalKnowledgeCandidate candidate) {
        CurrentActor actor = actorProvider.getRequiredActor();
        validateCandidate(candidate);
        if (repository.externalSourceExists(
                actor.tenantId(), candidate.sourceProvider(), candidate.sourceIdentifier())) {
            throw new BusinessConflictException(
                    "RAG_EXTERNAL_KNOWLEDGE_DUPLICATE", "This academic source already exists in the RAG knowledge base");
        }
        String content = draftContent(candidate);
        KnowledgeDocument draft = repository.insertExternalDraft(
                UUID.randomUUID(), actor.tenantId(), candidate.title().trim(), "FORENSIC_GUIDE", content,
                actor.userId(), candidate.sourceProvider(), candidate.sourceIdentifier(),
                candidate.sourceUrl(), candidate.venueName(), candidate.publicationYear());
        auditService.record(actor.tenantId(), actor.userId(), "RAG_EXTERNAL_DRAFT_CREATED",
                KnowledgeDocumentService.RESOURCE_TYPE, draft.id(), Map.of(
                        "sourceProvider", candidate.sourceProvider(),
                        "sourceIdentifier", candidate.sourceIdentifier(),
                        "venue", candidate.venueCode(),
                        "publicationYear", candidate.publicationYear()));
        return draft;
    }

    private void validateCandidate(ExternalKnowledgeCandidate candidate) {
        if (!"OPENALEX".equals(candidate.sourceProvider())) {
            throw new IllegalArgumentException("Unsupported external knowledge provider");
        }
        if (!candidate.sourceIdentifier().matches("https://openalex\\.org/W[0-9]+")) {
            throw new IllegalArgumentException("Invalid OpenAlex work identifier");
        }
        if (candidate.title().isBlank() || candidate.title().length() > 200
                || candidate.abstractText().isBlank() || candidate.abstractText().length() > 30000) {
            throw new IllegalArgumentException("Invalid academic metadata content");
        }
        URI source = URI.create(candidate.sourceUrl());
        if (!"https".equalsIgnoreCase(source.getScheme())) {
            throw new IllegalArgumentException("Academic source URL must use HTTPS");
        }
    }

    private String draftContent(ExternalKnowledgeCandidate candidate) {
        String authors = candidate.authors().isEmpty() ? "未提供" : String.join("、", candidate.authors());
        return """
                # RAG 外部学术知识草稿

                > 审核提示：本草稿仅根据公开论文元数据和摘要生成，未下载或阅读论文全文。发布前请通过官方链接核对。

                ## 来源信息

                - 论文：%s
                - 作者：%s
                - 会议或期刊：%s（%s）
                - 发表年份：%d
                - DOI：%s
                - 元数据来源：OpenAlex
                - 官方或落地页：%s

                ## 原始摘要

                %s

                ## 待审核要点

                - 该论文是否与 AIGC 检测、媒体篡改检测、模型归因或内容溯源直接相关？
                - 摘要中的结论是否足以形成取证知识，是否需要人工阅读全文后补充适用范围？
                - 是否明确区分模型候选证据、来源凭证与人工审核结论？
                - 是否需要记录数据集、阈值、跨生成器泛化和失败场景？
                """.formatted(
                candidate.title(), authors, candidate.venueName(), candidate.venueCode(),
                candidate.publicationYear(), candidate.doi().isBlank() ? "未提供" : candidate.doi(),
                candidate.sourceUrl(), candidate.abstractText());
    }

    public record DiscoveryResult(
            String query, List<String> venueCodes, int fromYear, int toYear,
            List<ExternalKnowledgeCandidate> candidates) {}
}
