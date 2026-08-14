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
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
    private final FakePlanner planner;
    private final SkillRegistry skillRegistry;
    private final ToolRegistry toolRegistry;
    private final AgentPolicyEngine policyEngine;
    private final AuditService auditService;

    public AgentTaskService(
            AgentTaskRepository repository,
            InvestigationCaseRepository caseRepository,
            CurrentActorProvider actorProvider,
            AgentContextBuilder contextBuilder,
            FakePlanner planner,
            SkillRegistry skillRegistry,
            ToolRegistry toolRegistry,
            AgentPolicyEngine policyEngine,
            AuditService auditService) {
        this.repository = repository;
        this.caseRepository = caseRepository;
        this.actorProvider = actorProvider;
        this.contextBuilder = contextBuilder;
        this.planner = planner;
        this.skillRegistry = skillRegistry;
        this.toolRegistry = toolRegistry;
        this.policyEngine = policyEngine;
        this.auditService = auditService;
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
            List<FakePlanner.SkillSelection> plan = planner.plan(context, running.goal());
            List<String> executedSkills = new ArrayList<>();
            List<String> observationIds = new ArrayList<>();
            List<String> knowledgeRetrievalIds = new ArrayList<>();
            int remainingBudget = running.remainingStepBudget();
            long checkpointVersion = running.checkpointVersion();

            for (FakePlanner.SkillSelection selection : plan) {
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
                                "planner", "FAKE",
                                "reason", selection.reason(),
                                "planPosition", executedSkills.size() + 1,
                                "planSize", plan.size()));

                remainingBudget = consumeBudget(remainingBudget);
                AgentTool tool = toolRegistry.require(toolCode);
                Map<String, Object> toolInput = Map.of(
                        "caseId", investigationCase.id().toString(),
                        "goal", running.goal(),
                        "assetIds", context.assets().stream().map(MediaAsset::id).map(UUID::toString).toList());
                Map<String, Object> toolOutput = tool.execute(context, toolInput);
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
                    repository.appendStep(
                            actor.tenantId(), taskId, "KNOWLEDGE_RETRIEVAL_RECORDED", "SUCCEEDED",
                            skill.code(), tool.code(), Map.of("toolCode", tool.code()),
                            Map.of(
                                    "knowledgeRetrievalId", retrieval.id().toString(),
                                    "citationCount", retrieval.citations().size(),
                                    "knowledgeAvailable", retrieval.knowledgeAvailable()));
                } else {
                    AgentObservation observation = repository.insertObservation(
                            actor.tenantId(), taskId, investigationCase.id(), primaryAsset.id(),
                            evidenceTypeFor(skill.code()), summaryFor(skill.code(), context.assets().size()), toolOutput);
                    observationIds.add(observation.id().toString());
                    repository.appendStep(
                            actor.tenantId(), taskId, "OBSERVATION_RECORDED", "SUCCEEDED",
                            skill.code(), tool.code(), Map.of("toolCode", tool.code()),
                            Map.of(
                                    "observationId", observation.id().toString(),
                                    "evidenceType", observation.evidenceType(),
                                    "summary", observation.summary()));
                }

                checkpointVersion++;
                repository.insertCheckpoint(
                        actor.tenantId(), taskId, checkpointVersion,
                        Map.of(
                                "status", "SKILL_COMPLETED",
                                "completedSkills", List.copyOf(executedSkills),
                                "observationIds", List.copyOf(observationIds),
                                "knowledgeRetrievalIds", List.copyOf(knowledgeRetrievalIds),
                                "remainingStepBudget", remainingBudget));
                repository.appendStep(
                        actor.tenantId(), taskId, "CHECKPOINT_SAVED", "SUCCEEDED",
                        skill.code(), null,
                        Map.of("checkpointVersion", checkpointVersion),
                        Map.of("remainingStepBudget", remainingBudget));
            }

            remainingBudget = consumeBudget(remainingBudget);
            Map<String, Object> conclusion = Map.of(
                    "verdict", "INCONCLUSIVE",
                    "summary", "四个确定性 Skill 已完成媒体事实检查与知识检索；这些材料用于辅助人工调查，不能单独证明媒体由 AI 生成。",
                    "executedSkills", List.copyOf(executedSkills),
                    "observationIds", List.copyOf(observationIds),
                    "knowledgeRetrievalIds", List.copyOf(knowledgeRetrievalIds),
                    "limitations", List.of(
                            "C2PA verifier not configured",
                            "No AIGC classifier or manipulation localization model",
                            "RAG uses deterministic local embeddings and does not perform LLM synthesis"));
            repository.appendStep(
                    actor.tenantId(), taskId, "CONCLUSION_SYNTHESIZED", "SUCCEEDED",
                    FakePlanner.PLAN_CODE, null,
                    Map.of(
                            "observationIds", List.copyOf(observationIds),
                            "knowledgeRetrievalIds", List.copyOf(knowledgeRetrievalIds)), conclusion);
            if (!repository.complete(
                    actor.tenantId(), taskId, running.version(), FakePlanner.PLAN_CODE, FakePlanner.PLAN_VERSION,
                    remainingBudget, checkpointVersion, conclusion)) {
                throw new BusinessConflictException(
                        "AGENT_TASK_VERSION_CONFLICT", "Agent task changed while completing");
            }
            repository.appendStep(
                    actor.tenantId(), taskId, "TASK_COMPLETED", "SUCCEEDED",
                    FakePlanner.PLAN_CODE, null, Map.of(), Map.of("status", "COMPLETED"));
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

    private String evidenceTypeFor(String skillCode) {
        return switch (skillCode) {
            case SkillRegistry.INTEGRITY_SKILL -> "FILE_INTEGRITY";
            case SkillRegistry.METADATA_SKILL -> "IMAGE_METADATA";
            case SkillRegistry.SIMILARITY_SKILL -> "PERCEPTUAL_SIMILARITY";
            default -> throw new IllegalArgumentException("No evidence type for skill: " + skillCode);
        };
    }

    private String summaryFor(String skillCode, int assetCount) {
        return switch (skillCode) {
            case SkillRegistry.INTEGRITY_SKILL -> "已对 " + assetCount + " 个媒体文件完成 SHA-256、字节数与 MIME 完整性核验。";
            case SkillRegistry.METADATA_SKILL -> "已对 " + assetCount + " 个图片完成格式、尺寸与 EXIF 摘要提取。";
            case SkillRegistry.SIMILARITY_SKILL -> "已对案件内 " + assetCount + " 个图片完成 dHash 感知相似度比较。";
            default -> throw new IllegalArgumentException("No summary for skill: " + skillCode);
        };
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
}
