package com.originguard.agent.application;

import com.originguard.agent.domain.AgentCheckpoint;
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

            int remainingBudget = consumeBudget(running.remainingStepBudget());
            FakePlanner.SkillSelection selection = planner.select(context, running.goal());
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
                            "reason", selection.reason()));

            remainingBudget = consumeBudget(remainingBudget);
            AgentTool tool = toolRegistry.require(toolCode);
            Map<String, Object> toolInput = Map.of(
                    "caseId", investigationCase.id().toString(),
                    "assetIds", context.assets().stream().map(MediaAsset::id).map(UUID::toString).toList());
            Map<String, Object> toolOutput = tool.execute(context, toolInput);
            repository.appendStep(
                    actor.tenantId(), taskId, "TOOL_CALLED", "SUCCEEDED",
                    skill.code(), tool.code(), toolInput, toolOutput);

            MediaAsset primaryAsset = context.assets().stream().findFirst()
                    .orElseThrow(() -> new BusinessConflictException(
                            "AGENT_ASSET_REQUIRED", "Agent metadata inspection requires a linked media asset"));
            String summary = "已检查 " + context.assets().size()
                    + " 条媒体登记信息；当前 Mock Tool 未读取文件内容，不能据此判断是否为 AI 生成。";
            AgentObservation observation = repository.insertObservation(
                    actor.tenantId(), taskId, investigationCase.id(), primaryAsset.id(), summary, toolOutput);
            repository.appendStep(
                    actor.tenantId(), taskId, "OBSERVATION_RECORDED", "SUCCEEDED",
                    skill.code(), tool.code(),
                    Map.of("toolCode", tool.code()),
                    Map.of(
                            "observationId", observation.id().toString(),
                            "evidenceType", observation.evidenceType(),
                            "summary", observation.summary()));

            long checkpointVersion = running.checkpointVersion() + 1;
            repository.insertCheckpoint(
                    actor.tenantId(), taskId, checkpointVersion,
                    Map.of(
                            "status", "OBSERVED",
                            "selectedSkill", skill.code(),
                            "selectedSkillVersion", skill.version(),
                            "completedActions", List.of("CONTEXT_ASSEMBLED", "SKILL_SELECTED", "TOOL_CALLED"),
                            "observationIds", List.of(observation.id().toString()),
                            "remainingStepBudget", remainingBudget));
            repository.appendStep(
                    actor.tenantId(), taskId, "CHECKPOINT_SAVED", "SUCCEEDED",
                    skill.code(), null,
                    Map.of("checkpointVersion", checkpointVersion),
                    Map.of("remainingStepBudget", remainingBudget));

            remainingBudget = consumeBudget(remainingBudget);
            Map<String, Object> conclusion = Map.of(
                    "verdict", "INCONCLUSIVE",
                    "summary", "M2.1 Harness 已完成媒体元数据检查，但尚未接入文件内容和真实检测工具。",
                    "observationIds", List.of(observation.id().toString()),
                    "limitations", List.of(
                            "Mock Tool only",
                            "File bytes unavailable",
                            "No model or RAG evidence"));
            repository.appendStep(
                    actor.tenantId(), taskId, "CONCLUSION_SYNTHESIZED", "SUCCEEDED",
                    skill.code(), null,
                    Map.of("observationIds", List.of(observation.id().toString())), conclusion);
            if (!repository.complete(
                    actor.tenantId(), taskId, running.version(), skill.code(), skill.version(),
                    remainingBudget, checkpointVersion, conclusion)) {
                throw new BusinessConflictException(
                        "AGENT_TASK_VERSION_CONFLICT", "Agent task changed while completing");
            }
            repository.appendStep(
                    actor.tenantId(), taskId, "TASK_COMPLETED", "SUCCEEDED",
                    skill.code(), null, Map.of(), Map.of("status", "COMPLETED"));
            auditService.record(
                    actor.tenantId(),
                    actor.userId(),
                    "AGENT_TASK_COMPLETED",
                    InvestigationCaseService.RESOURCE_TYPE,
                    investigationCase.id(),
                    Map.of("agentTaskId", taskId.toString(), "skillCode", skill.code()));
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

    private AgentTaskDetails details(UUID tenantId, UUID taskId) {
        return new AgentTaskDetails(
                requireTask(tenantId, taskId),
                repository.findSteps(tenantId, taskId),
                repository.findObservations(tenantId, taskId),
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
            List<AgentCheckpoint> checkpoints) {
        public AgentTaskDetails {
            steps = List.copyOf(steps);
            observations = List.copyOf(observations);
            checkpoints = List.copyOf(checkpoints);
        }
    }
}
