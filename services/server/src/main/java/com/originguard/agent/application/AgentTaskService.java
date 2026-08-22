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

    public AgentArtifactContent readObservationArtifact(
            UUID taskId, UUID observationId, UUID artifactId) {
        CurrentActor actor = actorProvider.getRequiredActor();
        requireTask(actor.tenantId(), taskId);
        AgentObservation observation = repository.findObservation(actor.tenantId(), taskId, observationId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "AGENT_OBSERVATION_NOT_FOUND", "Agent observation was not found"));
        if (observation.assetId() == null || !"AIGC_DETECTION".equals(observation.evidenceType())) {
            throw new ResourceNotFoundException(
                    "AGENT_ARTIFACT_NOT_FOUND", "Agent visualization was not found");
        }
        Object artifactValue = observation.payload().get("attentionArtifact");
        if (!(artifactValue instanceof Map<?, ?> artifact)
                || !artifactId.toString().equals(String.valueOf(artifact.get("artifactId")))) {
            throw new ResourceNotFoundException(
                    "AGENT_ARTIFACT_NOT_FOUND", "Agent visualization was not found");
        }
        byte[] content = artifactStorage.readAttentionOverlay(
                actor.tenantId(), taskId, observation.assetId(), artifactId);
        return new AgentArtifactContent(content, "image/png");
    }

    @Transactional
    public AgentTaskDetails run(UUID taskId, long expectedVersion) {
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
                Map<String, Object> toolOutput = tool.execute(enrichedContext, toolInput);
                if (SkillRegistry.AIGC_DETECTION_SKILL.equals(skill.code())) {
                    aigcDetection = toolOutput;
                }
                repository.appendStep(
                        actor.tenantId(), taskId, "TOOL_CALLED", "SUCCEEDED",
                        skill.code(), tool.code(), toolInput, toolOutput);

                executedSkills.add(skill.code());
                if (SkillRegistry.RAG_SKILL.equals(skill.code())) {
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
                                    ? "取证知识检索获得 " + retrieval.citations().size() + " 条引用"
                                    : "当前知识库未返回可用引用",
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
                } else if (SkillRegistry.AIGC_DETECTION_SKILL.equals(skill.code())) {
                    List<Map<String, Object>> findings = findings(toolOutput, "AIDE");
                    for (Map<String, Object> finding : findings) {
                        UUID assetId = UUID.fromString(String.valueOf(finding.get("assetId")));
                        AgentObservation observation = repository.insertObservation(
                                actor.tenantId(), taskId, investigationCase.id(), assetId,
                                evidenceTypeFor(skill.code()), aigcFindingSummary(finding), finding);
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
                    plan, executedSkills, observationIds, knowledgeRetrievalIds, aigcDetection));
            conclusion.put("executionMode", "PLAN_ACT_OBSERVE_REPLAN_STOP");
            conclusion.put("replanCount", replanCount);
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
                    plan.planCode(), null, Map.of(), Map.of("status", "COMPLETED"));
            auditService.record(
                    actor.tenantId(),
                    actor.userId(),
                    "AGENT_TASK_COMPLETED",
                    InvestigationCaseService.RESOURCE_TYPE,
                    investigationCase.id(),
                    Map.of("agentTaskId", taskId.toString(), "executedSkills", List.copyOf(executedSkills)));
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
        return details(actor.tenantId(), taskId);
    }

    private List<String> skillCodes(List<AgentPlanner.SkillSelection> selections) {
        return selections.stream().map(AgentPlanner.SkillSelection::skillCode).toList();
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
            case SkillRegistry.AIGC_DETECTION_SKILL -> "AIDE 已分析 "
                    + toolOutput.getOrDefault("analyzedImageCount", 0)
                    + " 个图片，最高 AI 生成概率为 "
                    + percent(toolOutput.get("maximumSyntheticProbability"))
                    + "；该结果是候选模型证据，仍需人工复核。";
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

    private String aigcFindingSummary(Map<String, Object> finding) {
        Object explanationValue = finding.get("explanation");
        if (explanationValue instanceof Map<?, ?> explanation) {
            Object summary = explanation.get("summary");
            if (summary != null && !String.valueOf(summary).isBlank()) return String.valueOf(summary);
        }
        return "AIDE 已分析“" + finding.getOrDefault("filename", "当前图片") + "”，AI 生成概率为 "
                + percent(finding.get("syntheticProbability")) + "；该结果仍需人工复核。";
    }

    private Map<String, Object> conclusionFor(
            AgentPlanner.PlannerPlan plan,
            List<String> executedSkills,
            List<String> observationIds,
            List<String> knowledgeRetrievalIds,
            Map<String, Object> aigcDetection) {
        Map<String, Object> agentAssessment = objectMap(aigcDetection.get("agentAssessment"));
        String verdict = String.valueOf(agentAssessment.getOrDefault(
                "verdict", aigcDetection.getOrDefault("overallVerdict", "INCONCLUSIVE")));
        String score = percent(aigcDetection.get("maximumSyntheticProbability"));
        String generatedSummary = String.valueOf(agentAssessment.getOrDefault("summary", "")).trim();
        String summary = !generatedSummary.isBlank()
                ? generatedSummary
                : aigcDetection.isEmpty()
                ? "自动取证步骤已完成，但没有取得 AIDE 检测结果，当前证据不足以判断。"
                : "质量门控与 AIGC 证据融合已完成；AIDE 最高 AI 生成概率为 " + score
                        + "，Agent 初步判断为“" + verdictLabel(verdict) + "”。该结果不是审核员最终裁决。";
        List<String> limitations = new ArrayList<>();
        limitations.add("当前使用 AIDE 官方 0.5 边界形成实验性初步判断，尚未经过 OriginGuard 业务验证集校准");
        limitations.add("CLIP 只负责媒体类型与模型路由，不作为 AIGC 真伪证据");
        limitations.add("尚未配置 C2PA 内容凭证校验器和篡改区域定位模型");
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
            conclusion.put("aideSyntheticProbability", aigcDetection.get("maximumSyntheticProbability"));
        }
        conclusion.put("planner", plan.provider());
        conclusion.put("plannerSummary", plan.summary());
        conclusion.put("executedSkills", List.copyOf(executedSkills));
        conclusion.put("observationIds", List.copyOf(observationIds));
        conclusion.put("knowledgeRetrievalIds", List.copyOf(knowledgeRetrievalIds));
        conclusion.put("limitations", List.copyOf(limitations));
        return Map.copyOf(conclusion);
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
