package com.originguard.assistant.application;

import com.originguard.agent.application.AgentTaskService;
import com.originguard.agent.application.AgentTaskDispatcher;
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
import com.originguard.shared.application.BusinessConflictException;
import com.originguard.retrieval.application.RetrievalOrchestrator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.ObjectProvider;

@Service
public class AssistantWorkbenchService {
    private final AssistantConversationRepository repository;
    private final CurrentActorProvider actorProvider;
    private final WorkbenchLlmClient llmClient;
    private final RetrievalOrchestrator retrievalOrchestrator;
    private final MediaAssetService mediaAssetService;
    private final InvestigationCaseService investigationCaseService;
    private final AgentTaskService agentTaskService;
    private final WebSecurityInvestigationService webSecurityInvestigationService;
    private final ObjectProvider<AgentTaskDispatcher> taskDispatcher;

    public AssistantWorkbenchService(
            AssistantConversationRepository repository,
            CurrentActorProvider actorProvider,
            WorkbenchLlmClient llmClient,
            RetrievalOrchestrator retrievalOrchestrator,
            MediaAssetService mediaAssetService,
            InvestigationCaseService investigationCaseService,
            AgentTaskService agentTaskService,
            WebSecurityInvestigationService webSecurityInvestigationService,
            ObjectProvider<AgentTaskDispatcher> taskDispatcher) {
        this.repository = repository;
        this.actorProvider = actorProvider;
        this.llmClient = llmClient;
        this.retrievalOrchestrator = retrievalOrchestrator;
        this.mediaAssetService = mediaAssetService;
        this.investigationCaseService = investigationCaseService;
        this.agentTaskService = agentTaskService;
        this.webSecurityInvestigationService = webSecurityInvestigationService;
        this.taskDispatcher = taskDispatcher;
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

    public ConversationDetails respond(UUID conversationId, String content, List<UUID> requestedAssetIds) {
        CurrentActor actor = actorProvider.getRequiredActor();
        AssistantConversation conversation = requireConversation(actor, conversationId);
        List<AssistantMessage> history = repository.findMessages(actor.tenantId(), conversationId);
        List<UUID> normalizedAssetIds = requestedAssetIds == null ? List.of() : requestedAssetIds.stream().distinct().toList();
        if (normalizedAssetIds.size() > 8) {
            throw new BusinessConflictException("TOO_MANY_MEDIA_ATTACHMENTS", "A joint analysis supports at most 8 images");
        }
        List<UUID> contextAssetIds = normalizedAssetIds.isEmpty() ? mostRecentAssets(history) : normalizedAssetIds;
        List<MediaAsset> requestedAssets = normalizedAssetIds.stream()
                .map(id -> mediaAssetService.require(actor.tenantId(), id)).toList();
        WorkbenchLlmClient.RouteDecision route = !normalizedAssetIds.isEmpty()
                ? new WorkbenchLlmClient.RouteDecision(
                        WorkbenchLlmClient.Intent.MEDIA_ANALYSIS, modelSpecificQuery(content), true, "用户随消息上传了媒体")
                : llmClient.route(content, history, !contextAssetIds.isEmpty());

        Map<String, Object> userGrounding = new LinkedHashMap<>();
        userGrounding.put("routeIntent", route.intent().name());
        userGrounding.put("routeReason", route.reason());
        if (!requestedAssets.isEmpty()) {
            userGrounding.put("attachmentName", requestedAssets.getFirst().originalFilename());
            userGrounding.put("attachmentNames", requestedAssets.stream().map(MediaAsset::originalFilename).toList());
            userGrounding.put("assetIds", normalizedAssetIds.stream().map(UUID::toString).toList());
            userGrounding.put("attachmentCount", requestedAssets.size());
        }
        UUID primaryAssetId = normalizedAssetIds.isEmpty() ? null : normalizedAssetIds.getFirst();
        repository.insertMessage(
                actor.tenantId(), conversationId, "USER",
                route.intent() == WorkbenchLlmClient.Intent.MEDIA_ANALYSIS ? "AGENT_REQUEST" : "CHAT",
                content.trim(), primaryAssetId, null, userGrounding);
        if (history.isEmpty()) repository.updateTitle(
                actor.tenantId(), actor.userId(), conversationId, abbreviate(content, 42));

        history = repository.findMessages(actor.tenantId(), conversationId);
        if (route.intent() == WorkbenchLlmClient.Intent.NEEDS_ATTACHMENT
                || (route.intent() == WorkbenchLlmClient.Intent.MEDIA_ANALYSIS && contextAssetIds.isEmpty())) {
            repository.insertMessage(
                    actor.tenantId(), conversationId, "ASSISTANT", "ATTACHMENT_REQUIRED",
                    "要判断某个具体图像或视频，需要先在输入框旁上传媒体。上传后我会把你的问题转化为 Agent 任务，而不是仅凭文字猜测。",
                    null, null, Map.of("routeIntent", "NEEDS_ATTACHMENT"));
            return getConversation(conversationId);
        }
        if (route.intent() == WorkbenchLlmClient.Intent.MEDIA_ANALYSIS) {
            return runMediaAgent(actor, conversationId, content.trim(), contextAssetIds, history, route.needsWebSearch());
        }
        if (route.intent() == WorkbenchLlmClient.Intent.WEB_SECURITY_INVESTIGATION) {
            return runWebSecurityInvestigation(actor, conversationId, content.trim());
        }
        return answerDirectly(
                actor, conversationId, content.trim(), history,
                route.needsWebSearch(), route.needsKnowledgeRetrieval());
    }

    private ConversationDetails runWebSecurityInvestigation(
            CurrentActor actor, UUID conversationId, String question) {
        try {
            WebSecurityInvestigationService.Result result = webSecurityInvestigationService.investigate(question);
            List<LiveWebSearchClient.WebSource> sources = result.intelligence().sources();
            Map<String, Object> grounding = new LinkedHashMap<>();
            grounding.put("routeIntent", "WEB_SECURITY_INVESTIGATION");
            grounding.put("groundingModes", sources.isEmpty()
                    ? List.of("NETWORK_OBSERVATION")
                    : List.of("NETWORK_OBSERVATION", "LIVE_WEB_SEARCH"));
            grounding.put("webSecurityInvestigation", result.report());
            grounding.put("liveSearchProvider", result.intelligence().provider());
            grounding.put("liveWebSources", sources.stream().map(item -> Map.of(
                    "label", "W" + (sources.indexOf(item) + 1),
                    "provider", item.provider(), "title", item.title(), "url", item.url(),
                    "snippet", item.snippet(), "score", item.score(), "venue", item.venue(),
                    "publicationYear", item.publicationYear() == null ? 0 : item.publicationYear(),
                    "qualityTier", item.qualityTier(), "qualityReason", item.qualityReason())).toList());
            grounding.put("retrievalInfluenceSummary",
                    "公开网页结果仅作为威胁调查线索展示，未直接修改 URL、DNS 与 TLS 形成的确定性风险分。"
                            + "任何恶意定性仍需调查员核实。");
            grounding.put("retrievedContentIsEvidence", false);
            repository.insertMessage(
                    actor.tenantId(), conversationId, "ASSISTANT", "CHAT", result.answer(),
                    null, null, Map.copyOf(grounding));
        } catch (BusinessConflictException blocked) {
            repository.insertMessage(
                    actor.tenantId(), conversationId, "ASSISTANT", "ERROR",
                    webSecurityRefusal(blocked.code()), null, null,
                    Map.of("routeIntent", "WEB_SECURITY_INVESTIGATION", "securityPolicyCode", blocked.code()));
        }
        return getConversation(conversationId);
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
            CurrentActor actor, UUID conversationId, String question, List<UUID> assetIds,
            List<AssistantMessage> history, boolean needsWebSearch) {
        requireMediaAgentPermissions(actor);
        List<MediaAsset> assets = assetIds.stream().map(id -> mediaAssetService.require(actor.tenantId(), id)).toList();
        MediaAsset asset = assets.getFirst();
        UUID assetId = asset.id();
        String caseTitle = abbreviate(assets.size() > 1
                ? "多图联合真实性分析 · " + assets.size() + " 张图片"
                : "媒体真实性分析 · " + asset.originalFilename(), 200);
        String caseDescription = abbreviate(
                "用户在对话工作台提出：" + question + "。由工作台意图路由触发 Agent，需结合既定取证策略、媒体内容和知识来源形成可核验结果。",
                2000);
        InvestigationCaseService.CaseDetails created = investigationCaseService.create(
                caseTitle, caseDescription, CasePriority.NORMAL, assetIds);
        UUID caseId = created.investigationCase().id();
        InvestigationCaseService.CaseDetails ready = investigationCaseService.transition(
                caseId, created.investigationCase().version(), CaseStatus.READY);
        investigationCaseService.transition(
                caseId, ready.investigationCase().version(), CaseStatus.INVESTIGATING);
        String goal = abbreviate("回答用户对当前" + (assets.size() > 1 ? assets.size() + " 张图片" : "媒体")
                + "的具体问题：" + question
                + "。逐图检测并比较媒体类型、感知相似性、C2PA 内容凭证与 AIGC 模型信号，形成跨图联合结论；知识不得替代模型证据，最终结果需要用户人工核验。", 500);
        AgentTaskService.AgentTaskDetails pending = agentTaskService.create(caseId, goal, 13);
        UUID taskId = pending.task().id();
        AgentTaskDispatcher dispatcher = taskDispatcher.getIfAvailable();
        if (dispatcher != null) {
            repository.insertMessage(
                    actor.tenantId(), conversationId, "ASSISTANT", "CHAT",
                    assets.size() > 1
                            ? "多图联合分析任务已进入队列。我会逐图检测、比较相似关系、验证 C2PA 凭证，并在完成后给出联合结论。"
                            : "Agent 分析任务已进入队列。我会实时记录媒体识别、模型检测、检索与证据融合过程，完成后在这里给出结论。",
                    assetId, taskId, Map.of(
                            "caseId", caseId.toString(), "agentTaskId", taskId.toString(),
                            "agentStatus", "PENDING", "queued", true,
                            "assetIds", assetIds.stream().map(UUID::toString).toList(),
                            "attachmentNames", assets.stream().map(MediaAsset::originalFilename).toList(),
                            "attachmentCount", assets.size()));
            dispatcher.enqueue(taskId, actor.userId(), pending.task().version(), conversationId, assetId, question);
            return getConversation(conversationId);
        }
        try {
            AgentTaskService.AgentTaskDetails completed = agentTaskService.run(taskId, pending.task().version());
            completeQueuedAgent(conversationId, question, assetId, completed);
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

    public void completeQueuedAgent(
            UUID conversationId, String question, UUID assetId, AgentTaskService.AgentTaskDetails completed) {
        CurrentActor actor = actorProvider.getRequiredActor();
        UUID taskId = completed.task().id();
        if (repository.hasTerminalAgentMessage(actor.tenantId(), conversationId, taskId)) return;
        if (!"COMPLETED".equals(completed.task().status().name())) {
            repository.insertMessage(
                    actor.tenantId(), conversationId, "ASSISTANT", "ERROR",
                    "Agent 任务未能完成：" + safeText(completed.task().failureMessage())
                            + "。你仍可进入任务详情查看已经记录的步骤。",
                    assetId, taskId, Map.of(
                            "caseId", completed.task().caseId().toString(),
                            "agentTaskId", taskId.toString(), "agentStatus", completed.task().status().name()));
            return;
        }
        List<AssistantMessage> history = repository.findMessages(actor.tenantId(), conversationId);
        List<KnowledgeSearchResult> taskKnowledge = flattenTaskKnowledge(completed);
        List<LiveWebSearchClient.WebSource> academic = taskAcademicSources(completed);
        RetrievalOrchestrator.RetrievalBundle retrieval = new RetrievalOrchestrator.RetrievalBundle(
                taskKnowledge, academic, academic.isEmpty() ? "UNAVAILABLE" : "OPENALEX_ACADEMIC",
                RetrievalOrchestrator.Profile.PROFESSIONAL_FORENSICS,
                "专业检测优先已发布知识库与质量排序后的学术来源；检索材料只影响规划和解释。");
        String context = groundedContext(taskKnowledge, academic);
        String response = llmClient.explainAgentResult(question, history, agentFacts(completed), context);
        String influence = llmClient.summarizeRetrievalInfluence(question, response, context, true);
        Map<String, Object> grounding = grounding(retrieval, "MEDIA_ANALYSIS", influence);
        grounding.put("caseId", completed.task().caseId().toString());
        grounding.put("agentTaskId", taskId.toString());
        grounding.put("agentStatus", completed.task().status().name());
        grounding.put("humanReviewRequired", true);
        grounding.put("performance", completed.task().conclusion().getOrDefault("performance", Map.of()));
        grounding.put("multiImageAnalysis", completed.task().conclusion().getOrDefault("multiImageAnalysis", Map.of()));
        grounding.put("provenanceSummary", completed.task().conclusion().getOrDefault("provenanceSummary", Map.of()));
        repository.insertMessage(
                actor.tenantId(), conversationId, "ASSISTANT", "AGENT_RESULT", response,
                assetId, taskId, grounding);
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

    private List<UUID> mostRecentAssets(List<AssistantMessage> history) {
        for (int index = history.size() - 1; index >= 0; index--) {
            Object raw = history.get(index).grounding().get("assetIds");
            if (raw instanceof List<?> values) {
                List<UUID> ids = values.stream().map(String::valueOf).map(UUID::fromString).distinct().toList();
                if (!ids.isEmpty()) return ids;
            }
            if (history.get(index).assetId() != null) return List.of(history.get(index).assetId());
        }
        return List.of();
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

    private String safeText(String message) {
        return message == null || message.isBlank() ? "未知执行错误" : abbreviate(message, 400);
    }

    private String webSecurityRefusal(String code) {
        return switch (code) {
            case "WEB_SECURITY_URL_REQUIRED" -> "请提供一个完整的 `http://` 或 `https://` URL，我才能启动 Web 安全调查。";
            case "WEB_SECURITY_PRIVATE_TARGET_BLOCKED", "WEB_SECURITY_TARGET_BLOCKED" ->
                    "该目标被网络安全策略阻止。OriginGuard 不访问内网、回环、链路本地、保留地址、"
                            + "带凭据的 URL 或非标准 Web 端口，以避免 SSRF 和越权探测。";
            case "WEB_SECURITY_DNS_UNRESOLVED", "WEB_SECURITY_DNS_INTERRUPTED" ->
                    "目标域名当前无法完成受控 DNS 解析，因此没有继续进行网络探测。请核对 URL 后重试。";
            default -> "该 URL 未通过受控 Web 安全调查策略，系统没有访问目标。";
        };
    }

    public record ConversationDetails(
            AssistantConversation conversation, List<AssistantMessage> messages) {
        public ConversationDetails { messages = List.copyOf(messages); }
    }
}
