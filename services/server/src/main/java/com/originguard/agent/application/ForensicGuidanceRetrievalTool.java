package com.originguard.agent.application;

import com.originguard.knowledge.domain.KnowledgeSearchResult;
import com.originguard.retrieval.application.RetrievalOrchestrator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class ForensicGuidanceRetrievalTool implements AgentTool {
    public static final String CODE = "rag.retrieve_forensic_guidance";
    private final RetrievalOrchestrator orchestrator;

    public ForensicGuidanceRetrievalTool(RetrievalOrchestrator orchestrator) { this.orchestrator = orchestrator; }

    @Override public String code() { return CODE; }

    @Override
    public Map<String, Object> execute(AgentExecutionContext context, Map<String, Object> input) {
        String goal = String.valueOf(input.getOrDefault("goal", "AIGC media forensic investigation"));
        String query = String.join(" ", goal, context.investigationCase().title(),
                context.investigationCase().description(),
                "media integrity EXIF perceptual similarity AIGC evidence limitations review guidance");
        RetrievalOrchestrator.RetrievalBundle bundle = orchestrator.retrieve(
                new RetrievalOrchestrator.RetrievalRequest(
                        context.actor().tenantId(), query,
                        RetrievalOrchestrator.Profile.PROFESSIONAL_FORENSICS,
                        context.actor().hasPermission("knowledge:read"), true, 5, 5));
        List<Map<String, Object>> citations = bundle.localSources().stream()
                .map(this::citation).toList();
        List<Map<String, Object>> academicSources = bundle.webSources().stream().map(source -> Map.<String, Object>of(
                "provider", source.provider(), "title", source.title(), "url", source.url(),
                "snippet", source.snippet(), "score", source.score(), "venue", source.venue(),
                "publicationYear", source.publicationYear() == null ? 0 : source.publicationYear(),
                "qualityTier", source.qualityTier(), "qualityReason", source.qualityReason())).toList();
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("provider", "ORIGINGUARD_RETRIEVAL_ORCHESTRATOR");
        output.put("toolVersion", "2.0.0");
        output.put("retrievalMode", "LOCAL_HYBRID_PLUS_QUALITY_RANKED_ACADEMIC_WEB");
        output.put("embeddingProvider", "KNOWLEDGE_RETRIEVER");
        output.put("query", query);
        output.put("knowledgeAvailable", !citations.isEmpty() || !academicSources.isEmpty());
        output.put("citationCount", citations.size());
        output.put("citations", citations);
        output.put("academicSources", academicSources);
        output.put("webProvider", bundle.webProvider());
        output.put("webSourceCount", academicSources.size());
        output.put("webSearchStatus", academicSources.isEmpty() ? "COMPLETED_NO_RESULTS" : "COMPLETED_WITH_RESULTS");
        output.put("retrievalPolicy", bundle.policySummary());
        output.put("influenceSummary", "检索来源只参与方案选择、适用范围和局限解释；媒体真假概率与初步结论仍只来自实际执行的检测模型和媒体证据。");
        output.put("limitations", List.of(
                "期刊分区随年份和评价体系变化，系统只展示可验证的来源等级，不自动宣称具体分区",
                "检索知识是解释与规划依据，不是当前媒体真假的直接证据"));
        return Map.copyOf(output);
    }

    private Map<String, Object> citation(KnowledgeSearchResult result) {
        Map<String, Object> citation = new LinkedHashMap<>();
        citation.put("documentId", result.documentId().toString());
        citation.put("documentTitle", result.documentTitle());
        citation.put("documentType", result.documentType());
        citation.put("documentVersion", result.documentVersion());
        citation.put("chunkId", result.chunkId().toString());
        citation.put("chunkIndex", result.chunkIndex());
        citation.put("quote", result.quote());
        citation.put("semanticScore", result.semanticScore());
        citation.put("keywordScore", result.keywordScore());
        citation.put("hybridScore", result.hybridScore());
        return Map.copyOf(citation);
    }
}
