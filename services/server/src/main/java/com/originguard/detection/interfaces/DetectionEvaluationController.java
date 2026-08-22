package com.originguard.detection.interfaces;

import com.originguard.detection.application.DetectionEvaluationService;
import com.originguard.detection.domain.EvaluationRun;
import com.originguard.detection.domain.EvaluationSample;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
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
@RequestMapping("/api/v1/detection-evaluations")
public class DetectionEvaluationController {
    private final DetectionEvaluationService service;

    public DetectionEvaluationController(DetectionEvaluationService service) {
        this.service = service;
    }

    @GetMapping("/samples")
    @PreAuthorize("hasAuthority('model:read')")
    public List<EvaluationSample> samples() {
        return service.samples();
    }

    @PostMapping("/samples")
    @PreAuthorize("hasAuthority('model:manage')")
    @ResponseStatus(HttpStatus.CREATED)
    public EvaluationSample addSample(@Valid @RequestBody AddSampleRequest request) {
        return service.addSample(
                request.assetId(), request.groundTruth(), request.mediaCategory(), request.generatorName());
    }

    @DeleteMapping("/samples/{sampleId}")
    @PreAuthorize("hasAuthority('model:manage')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSample(@PathVariable UUID sampleId) {
        service.deleteSample(sampleId);
    }

    @GetMapping("/runs")
    @PreAuthorize("hasAuthority('model:read')")
    public List<EvaluationRun> runs() {
        return service.runs();
    }

    @PostMapping("/runs")
    @PreAuthorize("hasAuthority('model:manage')")
    @ResponseStatus(HttpStatus.CREATED)
    public EvaluationRun run(@Valid @RequestBody RunRequest request) {
        return service.run(request.evaluationThreshold());
    }

    public record AddSampleRequest(
            @NotNull UUID assetId,
            @NotNull EvaluationSample.GroundTruth groundTruth,
            @NotNull EvaluationSample.MediaCategory mediaCategory,
            @Size(max = 100) String generatorName) {}

    public record RunRequest(
            @DecimalMin("0.0") @DecimalMax("1.0") double evaluationThreshold) {}
}
