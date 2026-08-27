package com.originguard.assistant.application;

import com.originguard.agent.application.AgentTaskService;
import com.originguard.agent.domain.AgentKnowledgeCitation;
import com.originguard.agent.domain.AgentTask;
import com.originguard.assistant.domain.AssistantConversation;
import com.originguard.assistant.domain.AssistantMessage;
import com.originguard.assistant.infrastructure.AssistantConversationRepository;
import com.originguard.identity.application.CurrentActorProvider;
import com.originguard.identity.domain.CurrentActor;
import com.originguard.investigation.application.InvestigationCaseService;
import com.originguard.investigation.domain.CasePriority;
import com.originguard.investigation.domain.CaseStatus;
import com.originguard.knowledge.domain.KnowledgeSearchResult;
import com.originguard.media.application.MediaAssetService;
import com.originguard.media.domain.MediaAsset;
import com.originguard.shared.application.ResourceNotFoundException;
import com.originguard.retrieval.application.RetrievalOrchestrator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

@Service
public class AssistantWorkbenchService {
    private final AssistantConversationRepository repository;
    private final CurrentActorProvider actorProvider;
    private final WorkbenchLlmClient llmClient;
    private final RetrievalOrchestrator retrievalOrchestrator;
    private final MediaAssetService mediaAssetService;
    private final InvestigationCaseService investigationCaseService;
    private final AgentTaskService agentTaskService;

    public AssistantWorkbenchService(
            AssistantConversationRepository repository,
            CurrentActorProvider actorProvider,
            WorkbenchLlmClient llmClient,
            RetrievalOrchestrator retrievalOrchestrator,
            MediaAssetService mediaAssetService,
            InvestigationCaseService investigationCaseService,
            AgentTaskService agentTaskService) {
        this.repository = repository;
        this.actorProvider = actorProvider;
        this.llmClient = llmClient;
        this.retrievalOrchestrator = retrievalOrchestrator;
        this.mediaAssetService = mediaAssetService;
        this.investigationCaseService = investigationCaseService;
        this.agentTaskService = agentTaskService;
    }

    public ConversationDetails createConversation(String requestedTitle) {
        CurrentActor actor = actorProvider.getRequiredActor();
        String title = requestedTitle == null || requestedTitle.isBlank() ? "新的分析对话" : abbreviate(requestedTitle, 160);
        AssistantConversation conversation = repository.insertConversation(actor.tenantId(), actor.userId(), title);
        return new ConversationDetails(conversation, List.of());
    }

    public List<AssistantConversation> listConversations() {
        CurrentActor actor = actorProvider.getRequiredActor();
        return repository.findConversations(actor.tenantId(), actor.userId());
    }

    public ConversationDetails getConversation(UUID conversationId) {
        CurrentActor actor = actorProvider.getRequiredActor();
        AssistantConversation conversation = requireConversation(actor, conversationId);
        return new ConversationDetails(conversation, repository.findMessages(actor.tenantId(), conversationId));
    }

    public void deleteConversation(UUID conversationId) {
        CurrentActor actor = actorProvider.getRequiredActor();
        requireConversation(actor, conversationId);
        repository.deleteConversation(actor.tenantId(), actor.userId(), conversationId);
    }

    public ConversationDetails respond(UUID conversationId, String content, UUID requestedAssetId) {
        CurrentActor actor = actorProvider.getRequiredActor();
        AssistantConversation conversation = requireConversation(actor, conversationId);
        List<AssistantMessage> history = repository.findMessages(actor.tenantId(), conversationId);
        UUID contextAssetId = requestedAssetId != null ? requestedAssetId : mostRecentAsset(history);
        MediaAsset requestedAsset = requestedAssetId == null ? null : mediaAssetService.require(actor.tenantId(), requestedAssetId);
        WorkbenchLlmClient.RouteDecision route = requestedAssetId != null
                ? new WorkbenchLlmClient.RouteDecision(
                        WorkbenchLlmClient.Intent.MEDIA_ANALYSIS, modelSpecificQuery(content), true, "用户随消息上传了媒体")
                : llmClient.route(content, history, contextAssetId != null);

        Map<String, Object> userGrounding = new LinkedHashMap<>();
        userGrounding.put("routeIntent", route.intent().name());
        userGrounding.put("routeReason", route.reason());
        if (requestedAsset != null) userGrounding.put("attachmentName", requestedAsset.originalFilename());
        repository.insertMessage(
                actor.tenantId(), conversationId, "USER",
                route.intent() == WorkbenchLlmClient.Intent.MEDIA_ANALYSIS ? "AGENT_REQUEST" : "CHAT",
                content.trim(), requestedAssetId, null, userGrounding);
        if (history.isEmpty()) repository.updateTitle(
                actor.tenantId(), actor.userId(), conversationId, abbreviate(content, 42));

        history = repository.findMessages(actor.tenantId(), conversationId);
        if (route.intent() == WorkbenchLlmClient.Intent.NEEDS_ATTACHMENT
                || (route.intent() == WorkbenchLlmClient.Intent.MEDIA_ANALYSIS && contextAssetId == null)) {
            repository.insertMessage(
                    actor.tenantId(), conversationId, "ASSISTANT", "ATTACHMENT_REQUIRED",
                    "要判断某个具体图像或视频，需要先在输入框旁上传媒体。上传后我会把你的问题转化为 Agent 任务，而不是仅凭文字猜测。",
                    null, null, Map.of("routeIntent", "NEEDS_ATTACHMENT"));
            return getConversation(conversationId);
        }
        if (route.intent() == WorkbenchLlmClient.Intent.MEDIA_ANALYSIS) {
            return runMediaAgent(actor, conversationId, content.trim(), contextAssetId, history, route.needsWebSearch());
        }
        return answerDirectly(
                actor, conversationId, content.trim(), history,
                route.needsWebSearch(), route.needsKnowledgeRetrieval());
    }

    private ConversationDetails answerDirectly(
            CurrentActor actor, UUID conversationId, String question,
            List<AssistantMessage> history, boolean needsWebSearch, boolean needsKnowledgeRetrieval) {
        RetrievalOrchestrator.RetrievalBundle retrieval = retrievalOrchestrator.retrieve(
                new RetrievalOrchestrator.RetrievalRequest(
                        actor.tenantId(), question, RetrievalOrchestrator.Profile.GENERAL_ANSWER,
                        needsKnowledgeRetrieval && actor.hasPermission("knowledge:read"),
                        needsWebSearch, 5, 5));
        String context = groundedContext(retrieval.localSources(), retrieval.webSources());
        String answer = llmClient.answer(question, history, context);
        String influence = llmClient.summarizeRetrievalInfluence(question, answer, context, false);
        Map<String, Object> grounding = grounding(retrieval, "DIRECT_ANSWER", influence);
        repository.insertMessage(
                actor.tenantId(), conversationId, "ASSISTANT", "CHAT", answer,
                null, null, grounding);
        return getConversation(conversationId);
    }

    private ConversationDetails runMediaAgent(
            CurrentActor actor, UUID conversationId, String question, UUID assetId,
            List<AssistantMessage> history, boolean needsWebSearch) {
        requireMediaAgentPermissions(actor);
        MediaAsset asset = mediaAssetService.require(actor.tenantId(), assetId);
        String caseTitle = abbreviate("媒体真实性分析 · " + asset.originalFilename(), 200);
        String caseDescription = abbreviate(
                "用户在对话工作台提出：" + question + "。由工作台意图路由触发 Agent，需结合既定取证策略、媒体内容和知识来源形成可核验结果。",
                2000);
        InvestigationCaseService.CaseDetails created = investigationCaseService.create(
                caseTitle, caseDescription, CasePriority.NORMAL, List.of(assetId));
        UUID caseId = created.investigationCase().id();
        InvestigationCaseService.CaseDetails ready = investigationCaseService.transition(
                caseId, created.investigationCase().version(), CaseStatus.READY);
        investigationCaseService.transition(
                caseId, ready.investigationCase().version(), CaseStatus.INVESTIGATING);
        String goal = abbreviate("回答用户对当前媒体的具体问题：" + question
                + "。必须执行适用于该媒体类型的真实性分析，结合本地知识说明局限；知识不得替代模型证据，最终结果需要用户人工核验。", 500);
        AgentTaskService.AgentTaskDetails pending = agentTaskService.create(caseId, goal, 13);
        UUID taskId = pending.task().id();
        try {
            AgentTaskService.AgentTaskDetails completed = agentTaskService.run(taskId, pending.task().version());
            List<KnowledgeSearchResult> taskKnowledge = flattenTaskKnowledge(completed);
            List<LiveWebSearchClient.WebSource> academic = taskAcademicSources(completed);
            RetrievalOrchestrator.RetrievalBundle retrieval = new RetrievalOrchestrator.RetrievalBundle(
                    taskKnowledge, academic, academic.isEmpty() ? "UNAVAILABLE" : "OPENALEX_ACADEMIC",
                    RetrievalOrchestrator.Profile.PROFESSIONAL_FORENSICS,
                    "专业检测优先已发布知识库与质量排序后的学术来源；检索材料只影响规划和解释。" );
            String response = llmClient.explainAgentResult(
                    question, history, agentFacts(completed), groundedContext(taskKnowledge, academic));
            String influence = llmClient.summarizeRetrievalInfluence(
                    question, response, groundedContext(taskKnowledge, academic), true);
            Map<String, Object> grounding = grounding(retrieval, "MEDIA_ANALYSIS", influence);
            grounding.put("caseId", caseId.toString());
            grounding.put("agentTaskId", taskId.toString());
            grounding.put("agentStatus", completed.task().status().name());
            grounding.put("humanReviewRequired", true);
            repository.insertMessage(
                    actor.tenantId(), conversationId, "ASSISTANT", "AGENT_RESULT", response,
                    assetId, taskId, grounding);
        } catch (RuntimeException exception) {
            repository.insertMessage(
                    actor.tenantId(), conversationId, "ASSISTANT", "ERROR",
                    "Agent 任务已经建立，但执行未能完成：" + safeMessage(exception)
                            + "。你仍可进入任务详情查看已经记录的步骤。",
                    assetId, taskId, Map.of(
                            "caseId", caseId.toString(), "agentTaskId", taskId.toString(), "agentStatus", "FAILED"));
        }
        return getConversation(conversationId);
    }

    private List<KnowledgeSearchResult> flattenTaskKnowledge(AgentTaskService.AgentTaskDetails details) {
        List<KnowledgeSearchResult> results = new ArrayList<>();
        for (var retrieval : details.knowledgeRetrievals()) {
            for (AgentKnowledgeCitation citation : retrieval.citations()) {
                results.add(new KnowledgeSearchResult(
                        citation.documentId(), citation.documentTitle(), citation.documentType(),
                        citation.documentVersion(), citation.chunkId(), citation.chunkIndex(), citation.quote(),
                        citation.semanticScore(), citation.keywordScore(), citation.hybridScore()));
            }
        }
        return List.copyOf(results);
    }

    private String groundedContext(
            List<KnowledgeSearchResult> local, List<LiveWebSearchClient.WebSource> live) {
        List<String> blocks = new ArrayList<>();
        for (int index = 0; index < local.size(); index++) {
            KnowledgeSearchResult item = local.get(index);
            blocks.add("[L" + (index + 1) + "] " + item.documentTitle() + "（版本 "
                    + item.documentVersion() + "）：" + item.quote());
        }
        for (int index = 0; index < live.size(); index++) {
            LiveWebSearchClient.WebSource item = live.get(index);
            blocks.add("[W" + (index + 1) + "] " + item.title() + "\nURL: " + item.url()
                    + "\n摘要：" + item.snippet());
        }
        return blocks.isEmpty() ? "（本次没有可用的外部检索片段，请仅使用模型已有知识并说明不确定性）"
                : String.join("\n\n", blocks);
    }

    private Map<String, Object> grounding(
            RetrievalOrchestrator.RetrievalBundle retrieval,
            String routeIntent,
            String influenceSummary) {
        List<KnowledgeSearchResult> local = retrieval.localSources();
        List<LiveWebSearchClient.WebSource> live = retrieval.webSources();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("routeIntent", routeIntent);
        result.put("groundingModes", modes(local, live));
        result.put("retrievalProfile", retrieval.profile().name());
        result.put("retrievalPolicy", retrieval.policySummary());
        result.put("retrievalInfluenceSummary", influenceSummary);
        result.put("localKnowledgeSources", local.stream().map(item -> Map.of(
                "label", "L" + (local.indexOf(item) + 1),
                "title", item.documentTitle(),
                "documentId", item.documentId().toString(),
                "documentVersion", item.documentVersion(),
                "chunkId", item.chunkId().toString(),
                "quote", item.quote(),
                "score", item.hybridScore())).toList());
        result.put("liveSearchProvider", retrieval.webProvider());
        result.put("liveWebSources", live.stream().map(item -> Map.of(
                "label", "W" + (live.indexOf(item) + 1),
                "provider", item.provider(), "title", item.title(), "url", item.url(),
                "snippet", item.snippet(), "score", item.score(), "venue", item.venue(),
                "publicationYear", item.publicationYear() == null ? 0 : item.publicationYear(),
                "qualityTier", item.qualityTier(), "qualityReason", item.qualityReason())).toList());
        result.put("retrievedContentIsEvidence", false);
        return result;
    }

    private List<LiveWebSearchClient.WebSource> taskAcademicSources(AgentTaskService.AgentTaskDetails details) {
        List<LiveWebSearchClient.WebSource> result = new ArrayList<>();
        for (var step : details.steps()) {
            Object raw = step.output().get("academicSources");
            if (!(raw instanceof List<?> sources)) continue;
            for (Object value : sources) {
                if (!(value instanceof Map<?, ?> source)) continue;
                result.add(new LiveWebSearchClient.WebSource(
                        mapText(source, "provider", "OPENALEX"),
                        mapText(source, "title", "学术来源"),
                        mapText(source, "url", ""),
                        mapText(source, "snippet", ""),
                        number(source.get("score")), mapText(source, "venue", ""),
                        integer(source.get("publicationYear")),
                        mapText(source, "qualityTier", "ACADEMIC_OTHER"),
                        mapText(source, "qualityReason", "学术来源")));
            }
        }
        return List.copyOf(result);
    }

    private double number(Object value) { return value instanceof Number number ? number.doubleValue() : 0; }
    private String mapText(Map<?, ?> map, String key, String fallback) {
        Object value = map.get(key);
        return value == null ? fallback : String.valueOf(value);
    }
    private Integer integer(Object value) {
        return value instanceof Number number && number.intValue() > 0 ? number.intValue() : null;
    }

    private List<String> modes(List<KnowledgeSearchResult> local, List<LiveWebSearchClient.WebSource> live) {
        List<String> modes = new ArrayList<>(List.of("MODEL_KNOWLEDGE", "CONVERSATION_CONTEXT"));
        if (!local.isEmpty()) modes.add("PUBLISHED_KNOWLEDGE_BASE");
        if (!live.isEmpty()) modes.add("LIVE_WEB_SEARCH");
        return List.copyOf(modes);
    }

    private String agentFacts(AgentTaskService.AgentTaskDetails details) {
        AgentTask task = details.task();
        Object verdict = task.conclusion().getOrDefault("verdict", "INCONCLUSIVE");
        Object summary = task.conclusion().getOrDefault("summary", "Agent 已完成分析");
        Object confidence = task.conclusion().getOrDefault("confidence", "LOW");
        Object probability = task.conclusion().get("primaryModelSyntheticProbability");
        Object supportingSignals = task.conclusion().getOrDefault("supportingSignals", List.of());
        Object counterSignals = task.conclusion().getOrDefault("counterSignals", List.of());
        Object missingEvidence = task.conclusion().getOrDefault("missingEvidence", List.of());
        Object limitations = task.conclusion().getOrDefault("limitations", List.of());
        List<String> observations = details.observations().stream().map(item -> item.summary()).toList();
        return "任务状态=" + task.status() + "；初步判断=" + verdict + "；置信级别=" + confidence
                + (probability == null ? "" : "；主模型 AI 生成概率=" + probability)
                + "；系统摘要=" + summary
                + "；支持当前判断的信号=" + supportingSignals
                + "；反向或不确定信号=" + counterSignals
                + "；尚缺证据=" + missingEvidence
                + "；能力限制=" + limitations
                + "；已记录观察=" + observations;
    }

    private UUID mostRecentAsset(List<AssistantMessage> history) {
        for (int index = history.size() - 1; index >= 0; index--) {
            if (history.get(index).assetId() != null) return history.get(index).assetId();
        }
        return null;
    }

    private void requireMediaAgentPermissions(CurrentActor actor) {
        List<String> required = List.of("asset:read", "case:create", "case:update", "case:submit", "agent:run");
        if (!required.stream().allMatch(actor::hasPermission)) {
            throw new AccessDeniedException("Current account cannot start media Agent tasks from the workbench");
        }
    }

    private boolean modelSpecificQuery(String content) {
        String normalized = content.toLowerCase();
        return normalized.matches(".*(sora|midjourney|stable diffusion|flux|novelai|最新|实时|近期).*" );
    }

    private AssistantConversation requireConversation(CurrentActor actor, UUID id) {
        return repository.findConversation(actor.tenantId(), actor.userId(), id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "ASSISTANT_CONVERSATION_NOT_FOUND", "Assistant conversation was not found"));
    }

    private String abbreviate(String value, int max) {
        String normalized = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= max ? normalized : normalized.substring(0, max);
    }

    private String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? "未知执行错误" : abbreviate(message, 400);
    }

    public record ConversationDetails(
            AssistantConversation conversation, List<AssistantMessage> messages) {
        public ConversationDetails { messages = List.copyOf(messages); }
    }
}
