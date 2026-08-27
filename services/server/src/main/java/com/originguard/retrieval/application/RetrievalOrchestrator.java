package com.originguard.retrieval.application;

import com.originguard.assistant.application.LiveWebSearchClient;
import com.originguard.knowledge.application.KnowledgeRetriever;
import com.originguard.knowledge.domain.KnowledgeSearchResult;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** One policy boundary for local, academic and general-web retrieval. */
@Component
public class RetrievalOrchestrator {
    private final KnowledgeRetriever knowledgeRetriever;
    private final LiveWebSearchClient webSearch;

    public RetrievalOrchestrator(KnowledgeRetriever knowledgeRetriever, LiveWebSearchClient webSearch) {
        this.knowledgeRetriever = knowledgeRetriever;
        this.webSearch = webSearch;
    }

    public RetrievalBundle retrieve(RetrievalRequest request) {
        List<KnowledgeSearchResult> local = request.includeLocal()
                ? safeLocal(request.tenantId(), request.query(), request.localLimit()) : List.of();
        LiveWebSearchClient.SearchResponse response = request.allowWeb()
                ? request.profile() == Profile.PROFESSIONAL_FORENSICS
                    ? webSearch.searchAcademic(request.query(), Math.max(request.webLimit() * 3, request.webLimit()))
                    : webSearch.search(request.query(), request.webLimit())
                : new LiveWebSearchClient.SearchResponse("NOT_REQUESTED", List.of());
        List<LiveWebSearchClient.WebSource> web = request.profile() == Profile.PROFESSIONAL_FORENSICS
                ? prioritizeAcademic(response.sources(), request.webLimit())
                : response.sources().stream().limit(request.webLimit()).toList();
        String policy = request.profile() == Profile.PROFESSIONAL_FORENSICS
                ? "专业检测优先已发布知识库、可核验的顶级期刊/会议和 IEEE 学术来源；未核实的期刊分区不会被系统虚构。"
                : "普通问答按需融合模型知识、会话上下文、已发布知识库与实时网页。";
        return new RetrievalBundle(local, web, response.provider(), request.profile(), policy);
    }

    private List<KnowledgeSearchResult> safeLocal(UUID tenantId, String query, int limit) {
        try { return knowledgeRetriever.search(tenantId, query, limit); }
        catch (RuntimeException unavailable) { return List.of(); }
    }

    private List<LiveWebSearchClient.WebSource> prioritizeAcademic(
            List<LiveWebSearchClient.WebSource> sources, int limit) {
        List<LiveWebSearchClient.WebSource> ranked = new ArrayList<>(sources);
        ranked.sort(Comparator
                .comparingInt((LiveWebSearchClient.WebSource source) -> qualityRank(source.qualityTier()))
                .thenComparing(Comparator.comparingDouble(LiveWebSearchClient.WebSource::score).reversed()));
        return ranked.stream().limit(limit).toList();
    }

    private int qualityRank(String tier) {
        return switch (tier == null ? "" : tier) {
            case "CURATED_TOP_VENUE" -> 0;
            case "IEEE_JOURNAL" -> 1;
            case "ACADEMIC_JOURNAL" -> 2;
            default -> 3;
        };
    }

    public enum Profile { GENERAL_ANSWER, PROFESSIONAL_FORENSICS }

    public record RetrievalRequest(
            UUID tenantId, String query, Profile profile, boolean includeLocal,
            boolean allowWeb, int localLimit, int webLimit) {}

    public record RetrievalBundle(
            List<KnowledgeSearchResult> localSources,
            List<LiveWebSearchClient.WebSource> webSources,
            String webProvider,
            Profile profile,
            String policySummary) {
        public RetrievalBundle {
            localSources = List.copyOf(localSources);
            webSources = List.copyOf(webSources);
        }
    }
}
