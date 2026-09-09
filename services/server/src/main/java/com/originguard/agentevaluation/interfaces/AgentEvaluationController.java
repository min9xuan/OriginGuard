package com.originguard.agentevaluation.interfaces;

import com.originguard.agentevaluation.application.AgentEvaluationService;
import com.originguard.agentevaluation.domain.AgentEvaluationCase;
import com.originguard.agentevaluation.domain.AgentEvaluationRun;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/agent-evaluations")
public class AgentEvaluationController {
    private final AgentEvaluationService service;

    public AgentEvaluationController(AgentEvaluationService service) {
        this.service = service;
    }

    @GetMapping("/cases")
    @PreAuthorize("hasAuthority('model:read')")
    public List<AgentEvaluationCase> cases() {
        return service.cases();
    }

    @PostMapping("/cases")
    @PreAuthorize("hasAuthority('model:manage')")
    @ResponseStatus(HttpStatus.CREATED)
    public AgentEvaluationCase createCase(@Valid @RequestBody CreateCaseRequest request) {
        return service.createCase(new AgentEvaluationService.CreateCase(
                request.name(), request.description(), request.requiredSkillCodes(), request.forbiddenSkillCodes(),
                request.requiredEvidenceTypes(), request.forbiddenEvidenceTypes(), request.maxToolCalls(),
                request.maxReplans(), request.maxDurationMilliseconds(), request.minimumScore(),
                request.requireCompleted(), request.requireHumanReview()));
    }

    @DeleteMapping("/cases/{caseId}")
    @PreAuthorize("hasAuthority('model:manage')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteCase(@PathVariable UUID caseId) {
        service.deleteCase(caseId);
    }

    @GetMapping("/runs")
    @PreAuthorize("hasAuthority('model:read')")
    public List<AgentEvaluationRun> runs() {
        return service.runs();
    }

    @PostMapping("/runs")
    @PreAuthorize("hasAuthority('model:manage')")
    @ResponseStatus(HttpStatus.CREATED)
    public AgentEvaluationRun evaluate(@Valid @RequestBody EvaluateRequest request) {
        return service.evaluate(request.evaluationCaseId(), request.agentTaskId());
    }

    public record CreateCaseRequest(
            @NotBlank @Size(max = 160) String name,
            @Size(max = 1000) String description,
            @NotNull @Size(max = 30) List<@NotBlank @Size(max = 100) String> requiredSkillCodes,
            @NotNull @Size(max = 30) List<@NotBlank @Size(max = 100) String> forbiddenSkillCodes,
            @NotNull @Size(max = 30) List<@NotBlank @Size(max = 100) String> requiredEvidenceTypes,
            @NotNull @Size(max = 30) List<@NotBlank @Size(max = 100) String> forbiddenEvidenceTypes,
            @Min(1) @Max(100) int maxToolCalls,
            @Min(0) @Max(50) int maxReplans,
            @Min(1_000) @Max(86_400_000) long maxDurationMilliseconds,
            @Min(0) @Max(100) int minimumScore,
            boolean requireCompleted,
            boolean requireHumanReview) {}

    public record EvaluateRequest(@NotNull UUID evaluationCaseId, @NotNull UUID agentTaskId) {}
}
