package com.originguard.agent.application;

import com.originguard.knowledge.application.KnowledgeRetriever;
import com.originguard.knowledge.domain.KnowledgeSearchResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class ForensicGuidanceRetrievalTool implements AgentTool {
    public static final String CODE = "rag.retrieve_forensic_guidance";
    private final KnowledgeRetriever retriever;

    public ForensicGuidanceRetrievalTool(KnowledgeRetriever retriever) { this.retriever = retriever; }

    @Override public String code() { return CODE; }

    @Override
    public Map<String, Object> execute(AgentExecutionContext context, Map<String, Object> input) {
        String goal = String.valueOf(input.getOrDefault("goal", "AIGC media forensic investigation"));
        String query = String.join(" ", goal, context.investigationCase().title(),
                context.investigationCase().description(),
                "media integrity EXIF perceptual similarity AIGC evidence limitations review guidance");
        List<Map<String, Object>> citations = retriever.search(context.actor().tenantId(), query, 5).stream()
                .map(this::citation).toList();
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("provider", "ORIGINGUARD_RAG");
        output.put("toolVersion", "1.0.0");
        output.put("retrievalMode", "POSTGRES_FTS_PGVECTOR_HYBRID");
        output.put("embeddingProvider", retriever.embeddingProviderCode());
        output.put("query", query);
        output.put("knowledgeAvailable", !citations.isEmpty());
        output.put("citationCount", citations.size());
        output.put("citations", citations);
        output.put("limitations", List.of(
                "Local deterministic hash vectors validate retrieval flow but are not production semantic embeddings",
                "Retrieved guidance is contextual reference and is not a forensic verdict"));
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
