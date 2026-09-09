package com.originguard.agentevaluation.application;

import com.originguard.agent.application.AgentTaskService;
import com.originguard.agent.domain.AgentTaskStatus;
import com.originguard.agentevaluation.domain.AgentEvaluationCase;
import com.originguard.agentevaluation.domain.AgentEvaluationRun;
import com.originguard.agentevaluation.infrastructure.AgentEvaluationRepository;
import com.originguard.audit.application.AuditService;
import com.originguard.identity.application.CurrentActorProvider;
import com.originguard.shared.application.BusinessConflictException;
import com.originguard.shared.application.ResourceNotFoundException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentEvaluationService {
    public static final String RESOURCE_TYPE = "AGENT_EVALUATION";

    private final AgentEvaluationRepository repository;
    private final AgentEvaluationScorer scorer;
    private final AgentTaskService agentTaskService;
    private final CurrentActorProvider actorProvider;
    private final AuditService auditService;

    public AgentEvaluationService(
            AgentEvaluationRepository repository,
            AgentEvaluationScorer scorer,
            AgentTaskService agentTaskService,
            CurrentActorProvider actorProvider,
            AuditService auditService) {
        this.repository = repository;
        this.scorer = scorer;
        this.agentTaskService = agentTaskService;
        this.actorProvider = actorProvider;
        this.auditService = auditService;
    }

    public List<AgentEvaluationCase> cases() {
        return repository.findCases(actorProvider.getRequiredActor().tenantId());
    }

    public List<AgentEvaluationRun> runs() {
        return repository.findRuns(actorProvider.getRequiredActor().tenantId());
    }

    @Transactional
    public AgentEvaluationCase createCase(CreateCase command) {
        var actor = actorProvider.getRequiredActor();
        UUID id = UUID.randomUUID();
        AgentEvaluationCase created = repository.insertCase(
                id, actor.tenantId(), command.name().trim(), normalize(command.description()),
                normalizeCodes(command.requiredSkillCodes()), normalizeCodes(command.forbiddenSkillCodes()),
                normalizeCodes(command.requiredEvidenceTypes()), normalizeCodes(command.forbiddenEvidenceTypes()),
                command.maxToolCalls(), command.maxReplans(), command.maxDurationMilliseconds(),
                command.minimumScore(), command.requireCompleted(), command.requireHumanReview(), actor.userId());
        auditService.record(actor.tenantId(), actor.userId(), "AGENT_EVALUATION_CASE_CREATED",
                RESOURCE_TYPE, id, Map.of("name", created.name()));
        return created;
    }

    @Transactional
    public void deleteCase(UUID id) {
        var actor = actorProvider.getRequiredActor();
        if (!repository.deleteCase(actor.tenantId(), id)) {
            throw new ResourceNotFoundException("AGENT_EVALUATION_CASE_NOT_FOUND", "Agent evaluation case was not found");
        }
        auditService.record(actor.tenantId(), actor.userId(), "AGENT_EVALUATION_CASE_DELETED",
                RESOURCE_TYPE, id, Map.of());
    }

    @Transactional
    public AgentEvaluationRun evaluate(UUID evaluationCaseId, UUID agentTaskId) {
        var actor = actorProvider.getRequiredActor();
        AgentEvaluationCase evaluationCase = repository.findCase(actor.tenantId(), evaluationCaseId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "AGENT_EVALUATION_CASE_NOT_FOUND", "Agent evaluation case was not found"));
        AgentTaskService.AgentTaskDetails details = agentTaskService.get(agentTaskId);
        if (details.task().status() == AgentTaskStatus.PENDING || details.task().status() == AgentTaskStatus.RUNNING) {
            throw new BusinessConflictException(
                    "AGENT_EVALUATION_TASK_NOT_TERMINAL", "Only terminal Agent tasks can be evaluated");
        }
        AgentEvaluationScorer.Score score = scorer.score(evaluationCase, details);
        UUID runId = UUID.randomUUID();
        AgentEvaluationRun run = repository.insertRun(
                runId, actor.tenantId(), evaluationCase, agentTaskId, details.task().status().name(),
                score.totalScore(), score.passed(), score.criticalFailure(), score.dimensionScores(),
                score.violations(), score.metrics(), actor.userId());
        auditService.record(actor.tenantId(), actor.userId(), "AGENT_EVALUATION_COMPLETED",
                RESOURCE_TYPE, runId, Map.of(
                        "evaluationCaseId", evaluationCaseId.toString(), "agentTaskId", agentTaskId.toString(),
                        "score", score.totalScore(), "passed", score.passed()));
        return run;
    }

    private List<String> normalizeCodes(List<String> values) {
        if (values == null) return List.of();
        return values.stream().map(String::trim).filter(value -> !value.isBlank()).distinct().toList();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    public record CreateCase(
            String name,
            String description,
            List<String> requiredSkillCodes,
            List<String> forbiddenSkillCodes,
            List<String> requiredEvidenceTypes,
            List<String> forbiddenEvidenceTypes,
            int maxToolCalls,
            int maxReplans,
            long maxDurationMilliseconds,
            int minimumScore,
            boolean requireCompleted,
            boolean requireHumanReview) {}
}
