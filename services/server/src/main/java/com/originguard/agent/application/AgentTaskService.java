package com.originguard.agent.application;

import com.originguard.agent.domain.AgentCheckpoint;
import com.originguard.agent.domain.AgentKnowledgeRetrieval;
import com.originguard.agent.domain.AgentObservation;
import com.originguard.agent.domain.AgentStep;
import com.originguard.agent.domain.AgentTask;
import com.originguard.agent.domain.AgentTaskStatus;
import com.originguard.agent.infrastructure.AgentTaskRepository;
import com.originguard.audit.application.AuditService;
import com.originguard.identity.application.CurrentActorProvider;
import com.originguard.identity.domain.CurrentActor;
import com.originguard.investigation.application.InvestigationCaseService;
import com.originguard.investigation.domain.CaseStatus;
import com.originguard.investigation.domain.InvestigationCase;
import com.originguard.investigation.infrastructure.InvestigationCaseRepository;
import com.originguard.media.domain.MediaAsset;
import com.originguard.shared.application.BusinessConflictException;
import com.originguard.shared.application.ResourceNotFoundException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentTaskService {
    public static final String RESOURCE_TYPE = "AGENT_TASK";

    private final AgentTaskRepository repository;
    private final InvestigationCaseRepository caseRepository;
    private final CurrentActorProvider actorProvider;
    private final AgentContextBuilder contextBuilder;
    private final AgentPlanner planner;
    private final AgentPlanValidator planValidator;
    private final SkillRegistry skillRegistry;
    private final ToolRegistry toolRegistry;
    private final AgentPolicyEngine policyEngine;
    private final AuditService auditService;
    private final AgentArtifactStorage artifactStorage;
    private final InvestigationCaseService investigationCaseService;
    private final int maxReplans;

    public AgentTaskService(
            AgentTaskRepository repository,
            InvestigationCaseRepository caseRepository,
            CurrentActorProvider actorProvider,
            AgentContextBuilder contextBuilder,
            AgentPlanner planner,
            AgentPlanValidator planValidator,
            SkillRegistry skillRegistry,
            ToolRegistry toolRegistry,
            AgentPolicyEngine policyEngine,
            AuditService auditService,
            AgentArtifactStorage artifactStorage,
            InvestigationCaseService investigationCaseService,
            @Value("${originguard.agent.max-replans:6}") int maxReplans) {
        this.repository = repository;
        this.caseRepository = caseRepository;
        this.actorProvider = actorProvider;
        this.contextBuilder = contextBuilder;
        this.planner = planner;
        this.planValidator = planValidator;
        this.skillRegistry = skillRegistry;
        this.toolRegistry = toolRegistry;
        this.policyEngine = policyEngine;
        this.auditService = auditService;
        this.artifactStorage = artifactStorage;
        this.investigationCaseService = investigationCaseService;
        this.maxReplans = Math.max(0, maxReplans);
    }

    @Transactional
    public AgentTaskDetails create(UUID caseId, String goal, int stepBudget) {
        CurrentActor actor = actorProvider.getRequiredActor();
        InvestigationCase investigationCase = requireCase(actor.tenantId(), caseId);
        requireAssignedInvestigator(investigationCase, actor);
        if (investigationCase.status() != CaseStatus.INVESTIGATING) {
            throw new BusinessConflictException(
                    "AGENT_CASE_NOT_INVESTIGATING", "Agent tasks can only be created while a case is investigating");
        }
        UUID taskId = UUID.randomUUID();
        repository.insertTask(taskId, actor.tenantId(), caseId, actor.userId(), goal.trim(), stepBudget);
        auditService.record(
                actor.tenantId(),
                actor.userId(),
                "AGENT_TASK_CREATED",
                InvestigationCaseService.RESOURCE_TYPE,
                caseId,
                Map.of("agentTaskId", taskId.toString(), "stepBudget", stepBudget));
        return details(actor.tenantId(), taskId);
    }

    public List<AgentTask> list() {
        CurrentActor actor = actorProvider.getRequiredActor();
        return repository.findAll(actor.tenantId());
    }

    public AgentTaskDetails get(UUID taskId) {
        CurrentActor actor = actorProvider.getRequiredActor();
        requireTask(actor.tenantId(), taskId);
        return details(actor.tenantId(), taskId);
    }

    @Transactional
    public void delete(UUID taskId) {
        CurrentActor actor = actorProvider.getRequiredActor();
        AgentTask task = requireTask(actor.tenantId(), taskId);
        requireTaskOwner(task, actor);
        if (task.status() == AgentTaskStatus.RUNNING) {
            throw new BusinessConflictException("AGENT_TASK_RUNNING", "正在运行的 Agent 任务不能删除");
        }
        if (!repository.delete(actor.tenantId(), taskId)) {
            throw new BusinessConflictException("AGENT_TASK_DELETE_CONFLICT", "Agent 任务状态已变化，请刷新后重试");
        }
        auditService.record(actor.tenantId(), actor.userId(), "AGENT_TASK_DELETED",
                InvestigationCaseService.RESOURCE_TYPE, task.caseId(), Map.of("agentTaskId", taskId.toString()));
    }

    public AgentArtifactContent readObservationArtifact(
            UUID taskId, UUID observationId, UUID artifactId) {
        CurrentActor actor = actorProvider.getRequiredActor();
        requireTask(actor.tenantId(), taskId);
        AgentObservation observation = repository.findObservation(actor.tenantId(), taskId, observationId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "AGENT_OBSERVATION_NOT_FOUND", "Agent observation was not found"));
        if (observation.assetId() == null || !("AIGC_DETECTION".equals(observation.evidenceType())
                || "MANIPULATION_LOCALIZATION".equals(observation.evidenceType()))) {
            throw new ResourceNotFoundException(
                    "AGENT_ARTIFACT_NOT_FOUND", "Agent visualization was not found");
        }
        Map<?, ?> artifact = observation.payload().values().stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .filter(value -> artifactId.toString().equals(String.valueOf(value.get("artifactId"))))
                .findFirst().orElse(null);
        if (artifact == null) {
            throw new ResourceNotFoundException(
                    "AGENT_ARTIFACT_NOT_FOUND", "Agent visualization was not found");
        }
        byte[] content = artifactStorage.readAttentionOverlay(
                actor.tenantId(), taskId, observation.assetId(), artifactId);
        return new AgentArtifactContent(content, "image/png");
    }

    public AgentTaskDetails run(UUID taskId, long expectedVersion) {
        long executionStartedNanos = System.nanoTime();
        CurrentActor actor = actorProvider.getRequiredActor();
        AgentTask pending = requireTask(actor.tenantId(), taskId);
        InvestigationCase investigationCase = requireCase(actor.tenantId(), pending.caseId());
        requireTaskOwner(pending, actor);
        requireAssignedInvestigator(investigationCase, actor);
        if (investigationCase.status() != CaseStatus.INVESTIGATING) {
            throw new BusinessConflictException(
                    "AGENT_CASE_NOT_INVESTIGATING", "Agent task cannot run outside the investigating state");
        }
        if (!repository.markRunning(actor.tenantId(), taskId, expectedVersion)) {
            throw new BusinessConflictException(
                    "AGENT_TASK_VERSION_CONFLICT", "Agent task changed or is no longer pending");
        }

        AgentTask running = requireTask(actor.tenantId(), taskId);
        long queueWaitMillis = running.startedAt() == null ? 0
                : Math.max(0, Duration.between(running.createdAt(), running.startedAt()).toMillis());
        repository.appendStep(
                actor.tenantId(), taskId, "TASK_DEQUEUED", "SUCCEEDED", null, null,
                Map.of("queuedAt", running.createdAt().toString()),
                Map.of("queueWaitMillis", queueWaitMillis));
        boolean completedSuccessfully = false;
        try {
            AgentExecutionContext context = contextBuilder.build(investigationCase, actor);
            repository.appendStep(
                    actor.tenantId(), taskId, "CONTEXT_ASSEMBLED", "SUCCEEDED", null, null,
                    Map.of("caseId", investigationCase.id().toString()),
                    Map.of(
                            "caseNumber", investigationCase.caseNumber(),
                            "caseStatus", investigationCase.status().name(),
                            "assetCount", context.assets().size(),
                            "humanEvidenceCount", context.humanEvidenceCount()));

            MediaAsset primaryAsset = context.assets().stream().findFirst()
                    .orElseThrow(() -> new BusinessConflictException(
                            "AGENT_ASSET_REQUIRED", "Deterministic analysis requires a linked media asset"));

            List<String> executedSkills = new ArrayList<>();
            List<String> observationIds = new ArrayList<>();
            List<String> knowledgeRetrievalIds = new ArrayList<>();
            Map<String, Object> aigcDetection = Map.of();
            Map<String, Object> manipulationLocalization = Map.of();
            Map<String, Object> retrievalEvidence = Map.of();
            Map<String, Object> integrityAnalysis = Map.of();
            Map<String, Object> similarityAnalysis = Map.of();
            int remainingBudget = running.remainingStepBudget();
            long checkpointVersion = running.checkpointVersion();

            remainingBudget = consumeBudget(remainingBudget);
            SkillDefinition mediaTypeSkill = skillRegistry.require(
                    SkillRegistry.MEDIA_TYPE_SKILL, SkillRegistry.SKILL_VERSION);
            String mediaTypeToolCode = mediaTypeSkill.allowedTools().iterator().next();
            policyEngine.requireCanRun(actor, investigationCase, mediaTypeSkill, mediaTypeToolCode);
            repository.appendStep(
                    actor.tenantId(), taskId, "SKILL_SELECTED", "SUCCEEDED",
                    mediaTypeSkill.code(), null,
                    Map.of("goal", running.goal()),
                    Map.of(
                            "skillCode", mediaTypeSkill.code(),
                            "skillVersion", mediaTypeSkill.version(),
                            "planner", "HARNESS_PRE_PLANNING",
                            "reason", "先识别媒体类型，并将结果作为 LLM 规划和后续模型解释的受控上下文"));
            remainingBudget = consumeBudget(remainingBudget);
            AgentTool mediaTypeTool = toolRegistry.require(mediaTypeToolCode);
            Map<String, Object> mediaTypeInput = Map.of(
                    "agentTaskId", taskId.toString(),
                    "caseId", investigationCase.id().toString(),
                    "goal", running.goal(),
                    "assetIds", context.assets().stream().map(MediaAsset::id).map(UUID::toString).toList());
            repository.appendStep(
                    actor.tenantId(), taskId, "TOOL_EXECUTION_STARTED", "SUCCEEDED",
                    mediaTypeSkill.code(), mediaTypeTool.code(),
                    Map.of("assetCount", context.assets().size()),
                    Map.of("message", "正在使用 CLIP 识别媒体内容类型"));
            Map<String, Object> mediaTypeOutput = mediaTypeTool.execute(context, mediaTypeInput);
            repository.appendStep(
                    actor.tenantId(), taskId, "TOOL_CALLED", "SUCCEEDED",
                    mediaTypeSkill.code(), mediaTypeTool.code(), mediaTypeInput, mediaTypeOutput);
            Map<UUID, Map<String, Object>> mediaTypeContexts = new LinkedHashMap<>();
            for (Map<String, Object> finding : findings(mediaTypeOutput, "CLIP")) {
                UUID assetId = UUID.fromString(String.valueOf(finding.get("assetId")));
                mediaTypeContexts.put(assetId, finding);
                AgentObservation observation = repository.insertObservation(
                        actor.tenantId(), taskId, investigationCase.id(), assetId,
                        evidenceTypeFor(mediaTypeSkill.code()), mediaTypeFindingSummary(finding), finding);
                observationIds.add(observation.id().toString());
                repository.appendStep(
                        actor.tenantId(), taskId, "OBSERVATION_RECORDED", "SUCCEEDED",
                        mediaTypeSkill.code(), mediaTypeTool.code(),
                        Map.of("toolCode", mediaTypeTool.code(), "assetId", assetId.toString()),
                        Map.of(
                                "observationId", observation.id().toString(),
                                "evidenceType", observation.evidenceType(),
                                "summary", observation.summary()));
            }
            AgentExecutionContext enrichedContext = context.withMediaTypeContexts(mediaTypeContexts);
            executedSkills.add(mediaTypeSkill.code());
            checkpointVersion++;
            repository.insertCheckpoint(
                    actor.tenantId(), taskId, checkpointVersion,
                    Map.of(
                            "status", "MEDIA_TYPE_CONTEXT_READY",
                            "completedSkills", List.copyOf(executedSkills),
                            "observationIds", List.copyOf(observationIds),
                            "knowledgeRetrievalIds", List.copyOf(knowledgeRetrievalIds),
                            "remainingStepBudget", remainingBudget));
            repository.appendStep(
                    actor.tenantId(), taskId, "CHECKPOINT_SAVED", "SUCCEEDED",
                    mediaTypeSkill.code(), null,
                    Map.of("checkpointVersion", checkpointVersion),
                    Map.of("remainingStepBudget", remainingBudget));

            repository.appendStep(
                    actor.tenantId(), taskId, "PLAN_REQUESTED", "SUCCEEDED",
                    null, null,
                    Map.of(
                            "caseId", investigationCase.id().toString(),
                            "assetCount", enrichedContext.assets().size(),
                            "mediaTypeContextCount", enrichedContext.mediaTypeContexts().size()),
                    Map.of("message", "规划器正在读取案件目标、媒体类型和取证知识，准备制定调查方案"));
            AgentPlanner.PlannerPlan generatedPlan = planner.plan(enrichedContext, running.goal());
            repository.appendStep(
                    actor.tenantId(), taskId, "PLAN_GENERATED", "SUCCEEDED",
                    generatedPlan.planCode(), null,
                    Map.of("goal", running.goal(), "assetCount", enrichedContext.assets().size()),
                    Map.of(
                            "provider", generatedPlan.provider(),
                            "planCode", generatedPlan.planCode(),
                            "planVersion", generatedPlan.planVersion(),
                            "summary", generatedPlan.summary(),
                            "selectedSkillCodes", generatedPlan.skills().stream()
                                    .map(AgentPlanner.SkillSelection::skillCode).toList(),
                            "selectedSkills", generatedPlan.skills().stream()
                                    .map(selection -> Map.of(
                                            "skillCode", selection.skillCode(),
                                            "skillVersion", selection.skillVersion(),
                                            "reason", selection.reason()))
                                    .toList(),
                            "trace", generatedPlan.trace()));
            AgentPlanner.PlannerPlan plan = planValidator.validate(
                    generatedPlan, remainingBudget);
            repository.appendStep(
                    actor.tenantId(), taskId, "PLAN_VALIDATED", "SUCCEEDED",
                    plan.planCode(), null,
                    Map.of("selectedSkillCount", plan.skills().size()),
                    Map.of(
                            "policyRequiredSkills", skillRegistry.list().stream()
                                    .filter(SkillDefinition::required).map(SkillDefinition::code).toList(),
                            "requiredStepBudget", 2 + plan.skills().size() * 2 + 1));

            int planPosition = 0;
            int replanCount = 0;
            boolean replanningEnabled = maxReplans > 0;
            List<AgentPlanner.SkillSelection> pendingSkills = new ArrayList<>(plan.skills());
            while (!pendingSkills.isEmpty()) {
                AgentPlanner.SkillSelection selection = pendingSkills.removeFirst();
                List<AgentPlanner.ObservationDigest> latestObservations = new ArrayList<>();
                planPosition++;
                remainingBudget = consumeBudget(remainingBudget);
                SkillDefinition skill = skillRegistry.require(selection.skillCode(), selection.skillVersion());
                String toolCode = skill.allowedTools().iterator().next();
                policyEngine.requireCanRun(actor, investigationCase, skill, toolCode);
                repository.appendStep(
                        actor.tenantId(), taskId, "SKILL_SELECTED", "SUCCEEDED",
                        skill.code(), null,
                        Map.of("goal", running.goal()),
                        Map.of(
                                "skillCode", skill.code(),
                                "skillVersion", skill.version(),
                                "planner", plan.provider(),
                                "reason", selection.reason(),
                                "planPosition", planPosition,
                                "remainingPlanSize", pendingSkills.size()));

                remainingBudget = consumeBudget(remainingBudget);
                AgentTool tool = toolRegistry.require(toolCode);
                Map<String, Object> toolInput = Map.of(
                        "agentTaskId", taskId.toString(),
                        "caseId", investigationCase.id().toString(),
                        "goal", running.goal(),
                        "assetIds", enrichedContext.assets().stream().map(MediaAsset::id).map(UUID::toString).toList(),
                        "mediaTypeContexts", mediaTypeContextsForTool(enrichedContext.mediaTypeContexts()));
                repository.appendStep(
                        actor.tenantId(), taskId, "TOOL_EXECUTION_STARTED", "SUCCEEDED",
                        skill.code(), tool.code(),
                        Map.of("assetCount", enrichedContext.assets().size()),
                        Map.of("message", "正在执行“" + skill.description() + "”"));
                Map<String, Object> toolOutput = tool.execute(enrichedContext, toolInput);
                if (SkillRegistry.AIGC_DETECTION_SKILL.equals(skill.code())) {
                    aigcDetection = toolOutput;
                }
                if (SkillRegistry.MANIPULATION_LOCALIZATION_SKILL.equals(skill.code())) {
                    manipulationLocalization = toolOutput;
                }
                if (SkillRegistry.INTEGRITY_SKILL.equals(skill.code())) integrityAnalysis = toolOutput;
                if (SkillRegistry.SIMILARITY_SKILL.equals(skill.code())) similarityAnalysis = toolOutput;
                repository.appendStep(
                        actor.tenantId(), taskId, "TOOL_CALLED", "SUCCEEDED",
                        skill.code(), tool.code(), toolInput, toolOutput);

                executedSkills.add(skill.code());
                if (SkillRegistry.RAG_SKILL.equals(skill.code())) {
                    retrievalEvidence = toolOutput;
                    AgentKnowledgeRetrieval retrieval = repository.insertKnowledgeRetrieval(
                            actor.tenantId(), taskId, investigationCase.id(), skill.code(), tool.code(),
                            String.valueOf(toolOutput.get("query")),
                            String.valueOf(toolOutput.get("retrievalMode")),
                            String.valueOf(toolOutput.get("embeddingProvider")),
                            Boolean.TRUE.equals(toolOutput.get("knowledgeAvailable")), citations(toolOutput));
                    knowledgeRetrievalIds.add(retrieval.id().toString());
                    latestObservations.add(new AgentPlanner.ObservationDigest(
                            "FORENSIC_GUIDANCE",
                            retrieval.knowledgeAvailable()
                                    ? "取证检索获得 " + retrieval.citations().size() + " 条本地引用和 "
                                            + numberValue(toolOutput.get("webSourceCount")) + " 条实时学术来源"
                                    : "本地知识库与实时学术检索均未返回可用来源",
                            Map.of(
                                    "knowledgeRetrievalId", retrieval.id().toString(),
                                    "citationCount", retrieval.citations().size(),
                                    "knowledgeAvailable", retrieval.knowledgeAvailable())));
                    repository.appendStep(
                            actor.tenantId(), taskId, "KNOWLEDGE_RETRIEVAL_RECORDED", "SUCCEEDED",
                            skill.code(), tool.code(), Map.of("toolCode", tool.code()),
                            Map.of(
                                    "knowledgeRetrievalId", retrieval.id().toString(),
                                    "citationCount", retrieval.citations().size(),
                                    "knowledgeAvailable", retrieval.knowledgeAvailable()));
                    repository.appendStep(
                            actor.tenantId(), taskId, "LIVE_RETRIEVAL_RECORDED", "SUCCEEDED",
                            skill.code(), tool.code(), Map.of("query", String.valueOf(toolOutput.get("query"))),
                            Map.of(
                                    "message", "实时学术检索已完成：" + toolOutput.getOrDefault("webProvider", "UNKNOWN")
                                            + " 返回 " + numberValue(toolOutput.get("webSourceCount")) + " 条来源",
                                    "provider", toolOutput.getOrDefault("webProvider", "UNKNOWN"),
                                    "sourceCount", numberValue(toolOutput.get("webSourceCount")),
                                    "status", toolOutput.getOrDefault("webSearchStatus", "UNKNOWN")));
                } else if (SkillRegistry.AIGC_DETECTION_SKILL.equals(skill.code())
                        || SkillRegistry.MANIPULATION_LOCALIZATION_SKILL.equals(skill.code())) {
                    List<Map<String, Object>> findings = findings(toolOutput, skill.description());
                    for (Map<String, Object> finding : findings) {
                        UUID assetId = UUID.fromString(String.valueOf(finding.get("assetId")));
                        AgentObservation observation = repository.insertObservation(
                                actor.tenantId(), taskId, investigationCase.id(), assetId,
                                evidenceTypeFor(skill.code()), findingSummary(skill.code(), finding), finding);
                        observationIds.add(observation.id().toString());
                        latestObservations.add(new AgentPlanner.ObservationDigest(
                                observation.evidenceType(), observation.summary(),
                                Map.of(
                                        "observationId", observation.id().toString(),
                                        "assetId", assetId.toString(),
                                        "skillCode", skill.code())));
                        repository.appendStep(
                                actor.tenantId(), taskId, "OBSERVATION_RECORDED", "SUCCEEDED",
                                skill.code(), tool.code(), Map.of("toolCode", tool.code(), "assetId", assetId.toString()),
                                Map.of(
                                        "observationId", observation.id().toString(),
                                        "evidenceType", observation.evidenceType(),
                                        "summary", observation.summary()));
                    }
                } else {
                    AgentObservation observation = repository.insertObservation(
                            actor.tenantId(), taskId, investigationCase.id(), primaryAsset.id(),
                            evidenceTypeFor(skill.code()),
                            summaryFor(skill.code(), enrichedContext.assets().size(), toolOutput),
                            toolOutput);
                    observationIds.add(observation.id().toString());
                    latestObservations.add(new AgentPlanner.ObservationDigest(
                            observation.evidenceType(), observation.summary(),
                            Map.of(
                                    "observationId", observation.id().toString(),
                                    "assetId", primaryAsset.id().toString(),
                                    "skillCode", skill.code())));
                    repository.appendStep(
                            actor.tenantId(), taskId, "OBSERVATION_RECORDED", "SUCCEEDED",
                            skill.code(), tool.code(), Map.of("toolCode", tool.code()),
                            Map.of(
                                    "observationId", observation.id().toString(),
                                    "evidenceType", observation.evidenceType(),
                                    "summary", observation.summary()));
                    if (SkillRegistry.INTEGRITY_SKILL.equals(skill.code())) {
                        for (Map<String, Object> finding : findings(toolOutput, "media integrity")) {
                            UUID findingAssetId = UUID.fromString(String.valueOf(finding.get("assetId")));
                            Map<String, Object> provenance = objectMap(finding.get("provenance"));
                            if (provenance.isEmpty()) continue;
                            Map<String, Object> payload = new LinkedHashMap<>(provenance);
                            payload.put("assetId", findingAssetId.toString());
                            payload.put("filename", finding.getOrDefault("filename", "当前图片"));
                            AgentObservation provenanceObservation = repository.insertObservation(
                                    actor.tenantId(), taskId, investigationCase.id(), findingAssetId,
                                    "CONTENT_PROVENANCE", provenanceSummary(finding, provenance), Map.copyOf(payload));
                            observationIds.add(provenanceObservation.id().toString());
                            latestObservations.add(new AgentPlanner.ObservationDigest(
                                    provenanceObservation.evidenceType(), provenanceObservation.summary(),
                                    Map.of("observationId", provenanceObservation.id().toString(),
                                            "assetId", findingAssetId.toString(), "skillCode", skill.code())));
                            repository.appendStep(
                                    actor.tenantId(), taskId, "OBSERVATION_RECORDED", "SUCCEEDED",
                                    skill.code(), tool.code(), Map.of("assetId", findingAssetId.toString()),
                                    Map.of("observationId", provenanceObservation.id().toString(),
                                            "evidenceType", provenanceObservation.evidenceType(),
                                            "summary", provenanceObservation.summary()));
                            repository.appendStep(
                                    actor.tenantId(), taskId, "PROVENANCE_VERIFIED", "SUCCEEDED",
                                    skill.code(), tool.code(), Map.of("assetId", findingAssetId.toString()),
                                    Map.of(
                                            "message", "C2PA 溯源校验完成：“"
                                                    + finding.getOrDefault("filename", "当前图片") + "” · "
                                                    + provenance.getOrDefault("status", "UNAVAILABLE"),
                                            "assetId", findingAssetId.toString(),
                                            "status", provenance.getOrDefault("status", "UNAVAILABLE"),
                                            "credentialPresent", provenance.getOrDefault("credentialPresent", false)));
                        }
                    }
                }

                AgentPlanner.ReplanDecision decision = null;
                String decisionStepType = "REPLAN_DECIDED";
                if (replanningEnabled) {
                    replanCount++;
                    if (replanCount > maxReplans) {
                        replanningEnabled = false;
                        replanCount = maxReplans;
                        repository.appendStep(
                                actor.tenantId(), taskId, "REPLAN_LIMIT_REACHED", "SUCCEEDED",
                                skill.code(), null,
                                Map.of("maxReplans", maxReplans),
                                Map.of("remainingSkillCodes", skillCodes(pendingSkills)));
                    } else {
                        repository.appendStep(
                                actor.tenantId(), taskId, "REPLAN_REQUESTED", "SUCCEEDED",
                                skill.code(), null,
                                Map.of(
                                        "decisionNumber", replanCount,
                                        "latestObservationCount", latestObservations.size(),
                                        "remainingSkillCodes", skillCodes(pendingSkills)),
                                Map.of("message", "规划器正在结合最新观察，判断继续、调整计划或停止"));
                        try {
                            decision = planValidator.validateDecision(
                                    planner.replan(new AgentPlanner.ReplanRequest(
                                            enrichedContext,
                                            running.goal(),
                                            plan,
                                            pendingSkills,
                                            executedSkills,
                                            latestObservations,
                                            remainingBudget,
                                            replanCount)),
                                    pendingSkills,
                                    executedSkills,
                                    remainingBudget);
                        } catch (RuntimeException replanFailure) {
                            decisionStepType = "REPLAN_FALLBACK";
                            decision = pendingSkills.isEmpty()
                                    ? new AgentPlanner.ReplanDecision(
                                            AgentPlanner.ReplanAction.STOP,
                                            "动态重规划不可用；既定步骤已经完成，按当前事实进入汇总",
                                            List.of(),
                                            Map.of("fallbackReason", safeMessage(replanFailure)))
                                    : new AgentPlanner.ReplanDecision(
                                            AgentPlanner.ReplanAction.CONTINUE,
                                            "动态重规划不可用；为保证取证任务可恢复，继续执行已校验的原计划",
                                            pendingSkills,
                                            Map.of("fallbackReason", safeMessage(replanFailure)));
                        }
                        List<String> previousSkillCodes = skillCodes(pendingSkills);
                        pendingSkills = new ArrayList<>(decision.remainingSkills());
                        repository.appendStep(
                                actor.tenantId(), taskId, decisionStepType, "SUCCEEDED",
                                skill.code(), null,
                                Map.of(
                                        "decisionNumber", replanCount,
                                        "latestObservations", latestObservations,
                                        "previousRemainingSkillCodes", previousSkillCodes),
                                Map.of(
                                        "action", decision.action().name(),
                                        "summary", decision.summary(),
                                        "nextSkillCodes", skillCodes(pendingSkills),
                                        "trace", decision.trace()));
                    }
                }

                checkpointVersion++;
                Map<String, Object> checkpointState = new LinkedHashMap<>();
                checkpointState.put("status", "SKILL_COMPLETED");
                checkpointState.put("completedSkills", List.copyOf(executedSkills));
                checkpointState.put("pendingSkillCodes", skillCodes(pendingSkills));
                checkpointState.put("observationIds", List.copyOf(observationIds));
                checkpointState.put("knowledgeRetrievalIds", List.copyOf(knowledgeRetrievalIds));
                checkpointState.put("remainingStepBudget", remainingBudget);
                checkpointState.put("replanCount", replanCount);
                if (decision != null) {
                    checkpointState.put("lastDecision", Map.of(
                            "action", decision.action().name(),
                            "summary", decision.summary()));
                }
                repository.insertCheckpoint(
                        actor.tenantId(), taskId, checkpointVersion,
                        checkpointState);
                repository.appendStep(
                        actor.tenantId(), taskId, "CHECKPOINT_SAVED", "SUCCEEDED",
                        skill.code(), null,
                        Map.of("checkpointVersion", checkpointVersion),
                        Map.of("remainingStepBudget", remainingBudget));
            }

            remainingBudget = consumeBudget(remainingBudget);
            Map<String, Object> conclusion = new LinkedHashMap<>(conclusionFor(
                    plan, executedSkills, observationIds, knowledgeRetrievalIds, aigcDetection,
                    manipulationLocalization, retrievalEvidence, integrityAnalysis, similarityAnalysis));
            conclusion.put("executionMode", "PLAN_ACT_OBSERVE_REPLAN_STOP");
            conclusion.put("replanCount", replanCount);
            long executionDurationMillis = (System.nanoTime() - executionStartedNanos) / 1_000_000L;
            int cacheHits = cacheHitCount(enrichedContext.mediaTypeContexts())
                    + cacheHitCount(aigcDetection)
                    + cacheHitCount(manipulationLocalization);
            conclusion.put("performance", Map.of(
                    "queueWaitMillis", queueWaitMillis,
                    "executionDurationMillis", executionDurationMillis,
                    "endToEndMillis", queueWaitMillis + executionDurationMillis,
                    "cacheHitCount", cacheHits));
            repository.appendStep(
                    actor.tenantId(), taskId, "CONCLUSION_SYNTHESIZED", "SUCCEEDED",
                    plan.planCode(), null,
                    Map.of(
                            "observationIds", List.copyOf(observationIds),
                            "knowledgeRetrievalIds", List.copyOf(knowledgeRetrievalIds)), conclusion);
            if (!repository.complete(
                    actor.tenantId(), taskId, running.version(), plan.planCode(), plan.planVersion(),
                    remainingBudget, checkpointVersion, conclusion)) {
                throw new BusinessConflictException(
                        "AGENT_TASK_VERSION_CONFLICT", "Agent task changed while completing");
            }
            repository.appendStep(
                    actor.tenantId(), taskId, "TASK_COMPLETED", "SUCCEEDED",
                    plan.planCode(), null, Map.of(), Map.of(
                            "status", "COMPLETED",
                            "queueWaitMillis", queueWaitMillis,
                            "executionDurationMillis", executionDurationMillis,
                            "cacheHitCount", cacheHits));
            auditService.record(
                    actor.tenantId(),
                    actor.userId(),
                    "AGENT_TASK_COMPLETED",
                    InvestigationCaseService.RESOURCE_TYPE,
                    investigationCase.id(),
                    Map.of("agentTaskId", taskId.toString(), "executedSkills", List.copyOf(executedSkills)));
            completedSuccessfully = true;
        } catch (RuntimeException exception) {
            repository.appendStep(
                    actor.tenantId(), taskId, "TASK_FAILED", "FAILED", null, null,
                    Map.of(), Map.of("message", safeMessage(exception)));
            repository.fail(actor.tenantId(), taskId, "AGENT_EXECUTION_FAILED", safeMessage(exception));
            auditService.record(
                    actor.tenantId(),
                    actor.userId(),
                    "AGENT_TASK_FAILED",
                    InvestigationCaseService.RESOURCE_TYPE,
                    investigationCase.id(),
                    Map.of("agentTaskId", taskId.toString(), "message", safeMessage(exception)));
        }
        if (completedSuccessfully) {
            try {
                investigationCaseService.prepareConfirmationAfterAgent(
                        investigationCase.id(), investigationCase.version(), taskId);
            } catch (RuntimeException exception) {
                auditService.record(
                        actor.tenantId(),
                        actor.userId(),
                        "RESULT_CONFIRMATION_PREPARATION_FAILED",
                        InvestigationCaseService.RESOURCE_TYPE,
                        investigationCase.id(),
                        Map.of("agentTaskId", taskId.toString(), "message", safeMessage(exception)));
            }
        }
        return details(actor.tenantId(), taskId);
    }

    private List<String> skillCodes(List<AgentPlanner.SkillSelection> selections) {
        return selections.stream().map(AgentPlanner.SkillSelection::skillCode).toList();
    }

    private int cacheHitCount(Object value) {
        if (value instanceof Map<?, ?> map) {
            int result = Boolean.TRUE.equals(map.get("cacheHit")) ? 1 : 0;
            for (Object nested : map.values()) result += cacheHitCount(nested);
            return result;
        }
        if (value instanceof Iterable<?> values) {
            int result = 0;
            for (Object nested : values) result += cacheHitCount(nested);
            return result;
        }
        return 0;
    }

    @Transactional
    public AgentTaskDetails cancel(UUID taskId, long expectedVersion) {
        CurrentActor actor = actorProvider.getRequiredActor();
        AgentTask task = requireTask(actor.tenantId(), taskId);
        requireTaskOwner(task, actor);
        if (!repository.cancel(actor.tenantId(), taskId, expectedVersion)) {
            throw new BusinessConflictException(
                    "AGENT_TASK_VERSION_CONFLICT", "Only a pending task with the current version can be cancelled");
        }
        repository.appendStep(
                actor.tenantId(), taskId, "TASK_CANCELLED", "SUCCEEDED", null, null,
                Map.of(), Map.of("cancelledBy", actor.userId().toString()));
        auditService.record(
                actor.tenantId(),
                actor.userId(),
                "AGENT_TASK_CANCELLED",
                InvestigationCaseService.RESOURCE_TYPE,
                task.caseId(),
                Map.of("agentTaskId", taskId.toString()));
        return details(actor.tenantId(), taskId);
    }

    private int consumeBudget(int remaining) {
        if (remaining <= 0) {
            throw new BusinessConflictException(
                    "AGENT_STEP_BUDGET_EXHAUSTED", "Agent step budget was exhausted");
        }
        return remaining - 1;
    }

    private String plannerLimitation(String provider) {
        return "FAKE".equals(provider)
                ? "固定测试规划器只执行预设 Skill 顺序，不理解媒体内容"
                : "本地多模态模型只负责选择 Skill，不直接作出取证裁决";
    }

    private String evidenceTypeFor(String skillCode) {
        return switch (skillCode) {
            case SkillRegistry.INTEGRITY_SKILL -> "FILE_INTEGRITY";
            case SkillRegistry.METADATA_SKILL -> "IMAGE_METADATA";
            case SkillRegistry.SIMILARITY_SKILL -> "PERCEPTUAL_SIMILARITY";
            case SkillRegistry.MEDIA_TYPE_SKILL -> "MEDIA_TYPE_CLASSIFICATION";
            case SkillRegistry.AIGC_DETECTION_SKILL -> "AIGC_DETECTION";
            case SkillRegistry.MANIPULATION_LOCALIZATION_SKILL -> "MANIPULATION_LOCALIZATION";
            default -> throw new IllegalArgumentException("No evidence type for skill: " + skillCode);
        };
    }

    private String summaryFor(String skillCode, int assetCount, Map<String, Object> toolOutput) {
        return switch (skillCode) {
            case SkillRegistry.INTEGRITY_SKILL -> Boolean.TRUE.equals(toolOutput.get("allChecksPassed"))
                    ? "已对 " + assetCount + " 个媒体文件完成完整性核验，登记信息与实际文件一致。"
                    : "完整性核验发现登记信息与实际文件不一致，后续分析结果必须谨慎使用。";
            case SkillRegistry.METADATA_SKILL -> "已对 " + assetCount + " 个图片完成格式、尺寸与 EXIF 摘要提取。";
            case SkillRegistry.SIMILARITY_SKILL -> "已对案件内 " + assetCount + " 个图片完成 dHash 感知相似度比较，共形成 "
                    + toolOutput.getOrDefault("comparisonCount", 0) + " 组比较结果。";
            case SkillRegistry.AIGC_DETECTION_SKILL -> "生成内容鉴别模型已分析 "
                    + toolOutput.getOrDefault("analyzedImageCount", 0)
                    + " 个图片，最高 AI 生成概率为 "
                    + percent(toolOutput.get("maximumSyntheticProbability"))
                    + "；该结果是候选模型证据，仍需人工复核。";
            case SkillRegistry.MANIPULATION_LOCALIZATION_SKILL -> "Mesorch 已对 "
                    + toolOutput.getOrDefault("analyzedImageCount", 0)
                    + " 个图片执行局部篡改定位，其中 "
                    + toolOutput.getOrDefault("succeededImageCount", 0) + " 个取得定位结果。";
            default -> throw new IllegalArgumentException("No summary for skill: " + skillCode);
        };
    }

    private List<Map<String, Object>> findings(Map<String, Object> toolOutput, String source) {
        Object raw = toolOutput.get("findings");
        if (!(raw instanceof List<?> values) || values.isEmpty()) {
            throw new IllegalStateException(source + " did not return per-image findings");
        }
        List<Map<String, Object>> findings = new ArrayList<>();
        for (Object value : values) {
            if (!(value instanceof Map<?, ?> map)) continue;
            Map<String, Object> finding = new LinkedHashMap<>();
            map.forEach((key, item) -> finding.put(String.valueOf(key), item));
            findings.add(Map.copyOf(finding));
        }
        if (findings.isEmpty()) throw new IllegalStateException(source + " findings are invalid");
        return List.copyOf(findings);
    }

    private Map<String, Object> mediaTypeContextsForTool(
            Map<UUID, Map<String, Object>> contexts) {
        Map<String, Object> result = new LinkedHashMap<>();
        contexts.forEach((assetId, context) -> result.put(assetId.toString(), context));
        return Map.copyOf(result);
    }

    private String mediaTypeFindingSummary(Map<String, Object> finding) {
        return "CLIP 将“" + finding.getOrDefault("filename", "当前图片") + "”识别为“"
                + finding.getOrDefault("mediaTypeLabel", "类型不明确") + "”，类型相对匹配度为 "
                + percent(finding.get("mediaTypeScore"))
                + "；该结果用于规划检测策略，不直接判断是否由 AI 生成。";
    }

    private String findingSummary(String skillCode, Map<String, Object> finding) {
        return SkillRegistry.MANIPULATION_LOCALIZATION_SKILL.equals(skillCode)
                ? manipulationFindingSummary(finding)
                : aigcFindingSummary(finding);
    }

    private String manipulationFindingSummary(Map<String, Object> finding) {
        String filename = String.valueOf(finding.getOrDefault("filename", "当前图片"));
        if (!"SUCCEEDED".equals(finding.get("status"))) {
            return "“" + filename + "”未取得 Mesorch 篡改定位结果；已记录能力不可用状态，不据此判断。";
        }
        String classification = String.valueOf(finding.getOrDefault("classification", "INCONCLUSIVE"));
        String direction = "SUSPICIOUS_MANIPULATION".equals(classification)
                ? "发现疑似局部内容变化" : "未发现超过当前阈值的局部内容变化";
        return "Mesorch 已分析“" + filename + "”，" + direction + "；疑似篡改分数为 "
                + percent(finding.get("tamperedProbability")) + "，高响应区域约占 "
                + percent(finding.get("tamperedAreaRatio")) + "。当前阈值尚未校准，仍需人工核验。";
    }

    private String aigcFindingSummary(Map<String, Object> finding) {
        Map<String, Object> routing = objectMap(finding.get("modelRouting"));
        String routingNote = Boolean.TRUE.equals(routing.get("degraded"))
                ? " 当前按媒体类型降级使用通用模型，专用能力尚待接入。"
                : " 当前已使用与媒体类型匹配的可用模型能力。";
        Object explanationValue = finding.get("explanation");
        if (explanationValue instanceof Map<?, ?> explanation) {
            Object summary = explanation.get("summary");
            if (summary != null && !String.valueOf(summary).isBlank()) return String.valueOf(summary) + routingNote;
        }
        return "生成内容鉴别模型已分析“" + finding.getOrDefault("filename", "当前图片") + "”，AI 生成概率为 "
                + percent(finding.get("syntheticProbability")) + "；该结果仍需人工复核。" + routingNote;
    }

    private String provenanceSummary(Map<String, Object> finding, Map<String, Object> provenance) {
        String filename = String.valueOf(finding.getOrDefault("filename", "当前图片"));
        String status = String.valueOf(provenance.getOrDefault("status", "UNAVAILABLE"));
        return switch (status) {
            case "VERIFIED" -> "“" + filename + "”包含通过校验的 C2PA 内容凭证；这可证明凭证与文件绑定有效，但不等于内容本身真实。";
            case "INVALID" -> "“" + filename + "”的 C2PA 内容凭证未通过校验，需检查签名、绑定或编辑链异常。";
            case "NOT_FOUND" -> "“" + filename + "”未发现 C2PA 内容凭证；缺少凭证不能据此判断图片为真或为假。";
            case "NOT_CONFIGURED" -> "C2PA 校验器尚未配置，未对“" + filename + "”形成来源凭证结论。";
            default -> "“" + filename + "”的 C2PA 校验暂不可用，本次不把来源凭证作为判断依据。";
        };
    }

    private Map<String, Object> conclusionFor(
            AgentPlanner.PlannerPlan plan,
            List<String> executedSkills,
            List<String> observationIds,
            List<String> knowledgeRetrievalIds,
            Map<String, Object> aigcDetection,
            Map<String, Object> manipulationLocalization,
            Map<String, Object> retrievalEvidence,
            Map<String, Object> integrityAnalysis,
            Map<String, Object> similarityAnalysis) {
        Map<String, Object> agentAssessment = objectMap(aigcDetection.get("agentAssessment"));
        String verdict = String.valueOf(agentAssessment.getOrDefault(
                "verdict", aigcDetection.getOrDefault("overallVerdict", "INCONCLUSIVE")));
        String score = percent(aigcDetection.get("maximumSyntheticProbability"));
        String generatedSummary = String.valueOf(agentAssessment.getOrDefault("summary", "")).trim();
        String summary = !generatedSummary.isBlank()
                ? generatedSummary
                : aigcDetection.isEmpty()
                ? "自动取证步骤已完成，但没有取得生成内容鉴别结果，当前证据不足以判断。"
                : "质量门控与 AIGC 证据融合已完成；鉴别模型最高 AI 生成概率为 " + score
                        + "，Agent 初步判断为“" + verdictLabel(verdict) + "”。该结果仍需负责调查员确认。";
        List<String> limitations = new ArrayList<>();
        limitations.add("当前使用生成内容鉴别模型的 0.5 实验边界形成初步判断，尚未经过 OriginGuard 业务验证集校准");
        limitations.add("CLIP 只负责媒体类型与模型路由，不作为 AIGC 真伪证据");
        limitations.add("C2PA 只验证凭证、文件绑定和声明的编辑历史，不能单独证明画面真实或由 AI 生成");
        if (numberValue(manipulationLocalization.get("succeededImageCount")) == 0) {
            limitations.add("Mesorch 篡改定位当前未配置或不可用，本次没有形成像素级定位结论");
        } else {
            limitations.add("Mesorch 篡改定位阈值尚未使用 OriginGuard 业务验证集校准");
        }
        limitations.add(plannerLimitation(plan.provider()));
        Map<String, Object> conclusion = new LinkedHashMap<>();
        conclusion.put("verdict", verdict);
        conclusion.put("summary", summary);
        conclusion.put("assessmentLevel", "AGENT_PRELIMINARY");
        conclusion.put("humanReviewRequired", true);
        conclusion.put("synthesisSource", agentAssessment.getOrDefault("source", "DETERMINISTIC_TEMPLATE"));
        conclusion.put("confidence", agentAssessment.getOrDefault("confidence", "LOW"));
        conclusion.put("supportingSignals", agentAssessment.getOrDefault("supportingSignals", List.of()));
        conclusion.put("counterSignals", agentAssessment.getOrDefault("counterSignals", List.of()));
        conclusion.put("missingEvidence", agentAssessment.getOrDefault("missingEvidence", List.of()));
        conclusion.put("primaryModelClassification",
                aigcDetection.getOrDefault("overallClassification", "INCONCLUSIVE"));
        conclusion.put("fusionPolicyVersion", AigcEvidenceFusion.POLICY_VERSION);
        if (aigcDetection.get("maximumSyntheticProbability") != null) {
            conclusion.put("primaryModelSyntheticProbability", aigcDetection.get("maximumSyntheticProbability"));
        }
        conclusion.put("manipulationLocalization", Map.of(
                "provider", manipulationLocalization.getOrDefault("provider", "NOT_EXECUTED"),
                "analyzedImageCount", manipulationLocalization.getOrDefault("analyzedImageCount", 0),
                "succeededImageCount", manipulationLocalization.getOrDefault("succeededImageCount", 0),
                "status", numberValue(manipulationLocalization.get("succeededImageCount")) > 0
                        ? "COMPLETED" : "UNAVAILABLE"));
        conclusion.put("planner", plan.provider());
        conclusion.put("plannerSummary", plan.summary());
        conclusion.put("executedSkills", List.copyOf(executedSkills));
        conclusion.put("observationIds", List.copyOf(observationIds));
        conclusion.put("knowledgeRetrievalIds", List.copyOf(knowledgeRetrievalIds));
        conclusion.put("retrievalInfluenceSummary", retrievalEvidence.getOrDefault(
                "influenceSummary", "本次没有可用的外部检索来源影响方案或解释。"));
        conclusion.put("retrievalPolicy", retrievalEvidence.getOrDefault("retrievalPolicy", ""));
        conclusion.put("academicSources", retrievalEvidence.getOrDefault("academicSources", List.of()));
        conclusion.put("webRetrievalProvider", retrievalEvidence.getOrDefault("webProvider", "NOT_EXECUTED"));
        conclusion.put("webRetrievalStatus", retrievalEvidence.getOrDefault("webSearchStatus", "NOT_EXECUTED"));
        conclusion.put("webSourceCount", retrievalEvidence.getOrDefault("webSourceCount", 0));
        Map<String, Object> provenance = provenanceSummary(integrityAnalysis);
        conclusion.put("provenanceSummary", provenance);
        conclusion.put("multiImageAnalysis", multiImageAnalysis(
                aigcDetection, manipulationLocalization, similarityAnalysis, integrityAnalysis));
        conclusion.put("limitations", List.copyOf(limitations));
        return Map.copyOf(conclusion);
    }

    private Map<String, Object> provenanceSummary(Map<String, Object> integrityAnalysis) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> finding : optionalFindings(integrityAnalysis)) {
            Map<String, Object> provenance = objectMap(finding.get("provenance"));
            String status = String.valueOf(provenance.getOrDefault("status", "UNAVAILABLE"));
            counts.put(status, counts.getOrDefault(status, 0) + 1);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("assetId", finding.getOrDefault("assetId", ""));
            item.put("filename", finding.getOrDefault("filename", ""));
            item.put("status", status);
            item.put("credentialPresent", provenance.getOrDefault("credentialPresent", false));
            item.put("signer", provenance.getOrDefault("signer", ""));
            item.put("manifestCount", provenance.getOrDefault("manifestCount", 0));
            items.add(Map.copyOf(item));
        }
        return Map.of("counts", Map.copyOf(counts), "items", List.copyOf(items));
    }

    private Map<String, Object> multiImageAnalysis(
            Map<String, Object> aigcDetection,
            Map<String, Object> manipulationLocalization,
            Map<String, Object> similarityAnalysis,
            Map<String, Object> integrityAnalysis) {
        List<Map<String, Object>> detections = optionalFindings(aigcDetection);
        List<Map<String, Object>> localizations = optionalFindings(manipulationLocalization);
        Map<String, Map<String, Object>> localizationByAsset = new LinkedHashMap<>();
        for (Map<String, Object> finding : localizations) {
            localizationByAsset.put(String.valueOf(finding.get("assetId")), finding);
        }
        Map<String, Map<String, Object>> provenanceByAsset = new LinkedHashMap<>();
        for (Map<String, Object> finding : optionalFindings(integrityAnalysis)) {
            provenanceByAsset.put(String.valueOf(finding.get("assetId")), objectMap(finding.get("provenance")));
        }
        Map<String, Integer> verdictCounts = new LinkedHashMap<>();
        List<Map<String, Object>> perAsset = new ArrayList<>();
        for (Map<String, Object> detection : detections) {
            String assetId = String.valueOf(detection.getOrDefault("assetId", ""));
            String verdict = String.valueOf(detection.getOrDefault("classification", "INCONCLUSIVE"));
            verdictCounts.put(verdict, verdictCounts.getOrDefault(verdict, 0) + 1);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("assetId", assetId);
            item.put("filename", detection.getOrDefault("filename", assetId));
            item.put("classification", verdict);
            item.put("syntheticProbability", detection.getOrDefault("syntheticProbability", 0));
            addManipulationSummary(item, localizationByAsset.get(assetId));
            item.put("provenanceStatus", provenanceByAsset.getOrDefault(assetId, Map.of())
                    .getOrDefault("status", "UNAVAILABLE"));
            perAsset.add(Map.copyOf(item));
        }
        for (Map<String, Object> localization : localizations) {
            String assetId = String.valueOf(localization.getOrDefault("assetId", ""));
            if (detections.stream().anyMatch(item -> assetId.equals(String.valueOf(item.get("assetId"))))) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("assetId", assetId);
            item.put("filename", localization.getOrDefault("filename", assetId));
            item.put("classification", "INCONCLUSIVE");
            addManipulationSummary(item, localization);
            item.put("provenanceStatus", provenanceByAsset.getOrDefault(assetId, Map.of())
                    .getOrDefault("status", "UNAVAILABLE"));
            perAsset.add(Map.copyOf(item));
        }
        List<Map<String, Object>> relatedPairs = new ArrayList<>();
        Object comparisons = similarityAnalysis.get("comparisons");
        if (comparisons instanceof List<?> values) {
            for (Object value : values) {
                if (!(value instanceof Map<?, ?> raw)) continue;
                Map<String, Object> comparison = new LinkedHashMap<>();
                raw.forEach((key, item) -> comparison.put(String.valueOf(key), item));
                if (!"DIFFERENT".equals(String.valueOf(comparison.get("classification")))) {
                    relatedPairs.add(Map.copyOf(comparison));
                }
            }
        }
        int assetCount = Math.max(Math.max(detections.size(), localizations.size()),
                numberValue(similarityAnalysis.get("assetCount")));
        return Map.of(
                "assetCount", assetCount,
                "jointAnalysis", assetCount > 1,
                "perAsset", List.copyOf(perAsset),
                "verdictCounts", Map.copyOf(verdictCounts),
                "relatedPairs", List.copyOf(relatedPairs),
                "comparisonCount", numberValue(similarityAnalysis.get("comparisonCount")));
    }

    private void addManipulationSummary(Map<String, Object> target, Map<String, Object> localization) {
        if (localization == null) {
            target.put("manipulationStatus", "NOT_EXECUTED");
            return;
        }
        target.put("manipulationStatus", localization.getOrDefault("status", "UNAVAILABLE"));
        target.put("manipulationClassification", localization.getOrDefault("classification", "INCONCLUSIVE"));
        target.put("tamperedProbability", localization.getOrDefault("tamperedProbability", 0));
        target.put("tamperedAreaRatio", localization.getOrDefault("tamperedAreaRatio", 0));
    }

    private List<Map<String, Object>> optionalFindings(Map<String, Object> output) {
        Object raw = output.get("findings");
        if (!(raw instanceof List<?> values)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object value : values) {
            if (!(value instanceof Map<?, ?> map)) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            map.forEach((key, entry) -> item.put(String.valueOf(key), entry));
            result.add(Map.copyOf(item));
        }
        return List.copyOf(result);
    }

    private int numberValue(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private String percent(Object value) {
        if (!(value instanceof Number number)) return "未知";
        return String.format(java.util.Locale.ROOT, "%.1f%%", number.doubleValue() * 100.0);
    }

    private String verdictLabel(String verdict) {
        return switch (verdict) {
            case "LIKELY_SYNTHETIC" -> "倾向 AI 生成";
            case "LIKELY_AUTHENTIC" -> "倾向真实";
            case "CONFLICTING_EVIDENCE" -> "证据冲突";
            case "UNSUPPORTED_INPUT" -> "输入不适用";
            default -> "证据不足";
        };
    }

    private Map<String, Object> objectMap(Object value) {
        if (!(value instanceof Map<?, ?> raw)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, item) -> result.put(String.valueOf(key), item));
        return Map.copyOf(result);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> citations(Map<String, Object> toolOutput) {
        Object value = toolOutput.get("citations");
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().filter(Map.class::isInstance)
                .map(item -> (Map<String, Object>) item).toList();
    }

    private AgentTaskDetails details(UUID tenantId, UUID taskId) {
        return new AgentTaskDetails(
                requireTask(tenantId, taskId),
                repository.findSteps(tenantId, taskId),
                repository.findObservations(tenantId, taskId),
                repository.findKnowledgeRetrievals(tenantId, taskId),
                repository.findCheckpoints(tenantId, taskId));
    }

    private AgentTask requireTask(UUID tenantId, UUID taskId) {
        return repository.findById(tenantId, taskId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "AGENT_TASK_NOT_FOUND", "Agent task was not found"));
    }

    private InvestigationCase requireCase(UUID tenantId, UUID caseId) {
        return caseRepository.findById(tenantId, caseId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "CASE_NOT_FOUND", "Investigation case was not found"));
    }

    private void requireAssignedInvestigator(InvestigationCase investigationCase, CurrentActor actor) {
        if (!actor.userId().equals(investigationCase.assignedInvestigatorId())) {
            throw new AccessDeniedException("Only the assigned investigator can operate the agent task");
        }
    }

    private void requireTaskOwner(AgentTask task, CurrentActor actor) {
        if (!actor.userId().equals(task.createdBy())) {
            throw new AccessDeniedException("Only the task creator can run or cancel it");
        }
    }

    private String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    public record AgentTaskDetails(
            AgentTask task,
            List<AgentStep> steps,
            List<AgentObservation> observations,
            List<AgentKnowledgeRetrieval> knowledgeRetrievals,
            List<AgentCheckpoint> checkpoints) {
        public AgentTaskDetails {
            steps = List.copyOf(steps);
            observations = List.copyOf(observations);
            knowledgeRetrievals = List.copyOf(knowledgeRetrievals);
            checkpoints = List.copyOf(checkpoints);
        }
    }

    public record AgentArtifactContent(byte[] content, String contentType) {
        public AgentArtifactContent {
            content = content.clone();
        }
    }
}
