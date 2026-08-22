package com.originguard.detection.application;

import com.originguard.audit.application.AuditService;
import com.originguard.detection.application.DetectionMetricsCalculator.ScoredLabel;
import com.originguard.detection.domain.EvaluationRun;
import com.originguard.detection.domain.EvaluationSample;
import com.originguard.detection.infrastructure.DetectionEvaluationRepository;
import com.originguard.identity.application.CurrentActorProvider;
import com.originguard.identity.domain.CurrentActor;
import com.originguard.media.application.MediaAssetService;
import com.originguard.media.domain.MediaAsset;
import com.originguard.shared.application.BusinessConflictException;
import com.originguard.shared.application.ResourceNotFoundException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DetectionEvaluationService {
    public static final String RESOURCE_TYPE = "DETECTION_EVALUATION";

    private final DetectionEvaluationRepository repository;
    private final MediaAssetService mediaAssetService;
    private final AideInferenceClient inferenceClient;
    private final CurrentActorProvider actorProvider;
    private final AuditService auditService;

    public DetectionEvaluationService(
            DetectionEvaluationRepository repository,
            MediaAssetService mediaAssetService,
            AideInferenceClient inferenceClient,
            CurrentActorProvider actorProvider,
            AuditService auditService) {
        this.repository = repository;
        this.mediaAssetService = mediaAssetService;
        this.inferenceClient = inferenceClient;
        this.actorProvider = actorProvider;
        this.auditService = auditService;
    }

    public List<EvaluationSample> samples() {
        CurrentActor actor = actorProvider.getRequiredActor();
        return repository.findSamples(actor.tenantId());
    }

    public List<EvaluationRun> runs() {
        CurrentActor actor = actorProvider.getRequiredActor();
        return repository.findRuns(actor.tenantId());
    }

    @Transactional
    public EvaluationSample addSample(
            UUID assetId,
            EvaluationSample.GroundTruth groundTruth,
            EvaluationSample.MediaCategory mediaCategory,
            String generatorName) {
        CurrentActor actor = actorProvider.getRequiredActor();
        MediaAsset asset = mediaAssetService.require(actor.tenantId(), assetId);
        if (!asset.contentType().startsWith("image/") || !"STORED".equals(asset.storageStatus())) {
            throw new BusinessConflictException(
                    "EVALUATION_IMAGE_REQUIRED", "Only stored image assets can be added to the evaluation dataset");
        }
        if (repository.findSamples(actor.tenantId()).stream().anyMatch(sample -> sample.assetId().equals(assetId))) {
            throw new BusinessConflictException(
                    "EVALUATION_SAMPLE_DUPLICATE", "This media asset is already in the evaluation dataset");
        }
        String normalizedGenerator = generatorName == null ? "" : generatorName.trim();
        if (groundTruth == EvaluationSample.GroundTruth.AUTHENTIC) normalizedGenerator = "";
        UUID id = UUID.randomUUID();
        EvaluationSample sample = repository.insertSample(
                id, actor.tenantId(), assetId, groundTruth, mediaCategory, normalizedGenerator, actor.userId());
        auditService.record(actor.tenantId(), actor.userId(), "EVALUATION_SAMPLE_ADDED", RESOURCE_TYPE, id,
                Map.of("assetId", assetId.toString(), "groundTruth", groundTruth.name(),
                        "mediaCategory", mediaCategory.name()));
        return sample;
    }

    @Transactional
    public void deleteSample(UUID sampleId) {
        CurrentActor actor = actorProvider.getRequiredActor();
        if (!repository.deleteSample(actor.tenantId(), sampleId)) {
            throw new ResourceNotFoundException("EVALUATION_SAMPLE_NOT_FOUND", "Evaluation sample was not found");
        }
        auditService.record(actor.tenantId(), actor.userId(), "EVALUATION_SAMPLE_REMOVED",
                RESOURCE_TYPE, sampleId, Map.of());
    }

    @Transactional
    public EvaluationRun run(double evaluationThreshold) {
        CurrentActor actor = actorProvider.getRequiredActor();
        List<EvaluationSample> samples = repository.findSamples(actor.tenantId());
        List<ScoredLabel> labels = samples.stream()
                .map(sample -> new ScoredLabel(sample.groundTruth(), 0))
                .toList();
        if (!DetectionMetricsCalculator.hasBothClasses(labels)) {
            throw new BusinessConflictException(
                    "EVALUATION_BOTH_CLASSES_REQUIRED",
                    "The evaluation dataset must contain both authentic and synthetic images");
        }

        List<SampleInference> inferences = new ArrayList<>();
        for (EvaluationSample sample : samples) {
            MediaAssetService.StoredMedia stored = mediaAssetService.readStored(actor.tenantId(), sample.assetId());
            AideInferenceClient.Inference inference =
                    inferenceClient.detect(stored.content(), stored.asset().contentType());
            inferences.add(new SampleInference(sample, inference));
        }
        List<ScoredLabel> scored = inferences.stream()
                .map(item -> new ScoredLabel(item.sample().groundTruth(), item.inference().syntheticProbability()))
                .toList();
        double recommendedThreshold = DetectionMetricsCalculator.recommendThreshold(scored);
        EvaluationRun.Metrics metrics = DetectionMetricsCalculator.calculate(scored, evaluationThreshold);
        Map<String, EvaluationRun.CategoryMetrics> categoryMetrics = categoryMetrics(inferences);
        AideInferenceClient.Inference first = inferences.getFirst().inference();
        UUID runId = UUID.randomUUID();
        repository.insertRun(
                runId, actor.tenantId(), first.modelCode(), first.modelVersion(), evaluationThreshold,
                recommendedThreshold, metrics, categoryMetrics, actor.userId());
        for (SampleInference item : inferences) {
            EvaluationSample.GroundTruth predicted = item.inference().syntheticProbability() >= evaluationThreshold
                    ? EvaluationSample.GroundTruth.SYNTHETIC
                    : EvaluationSample.GroundTruth.AUTHENTIC;
            repository.insertResult(
                    UUID.randomUUID(), actor.tenantId(), runId, item.sample(),
                    item.inference().syntheticProbability(), predicted,
                    item.inference().processingMilliseconds(), item.inference().qualityStatus());
        }
        auditService.record(actor.tenantId(), actor.userId(), "DETECTION_EVALUATION_COMPLETED",
                RESOURCE_TYPE, runId, Map.of(
                        "sampleCount", metrics.sampleCount(), "f1", metrics.f1(),
                        "evaluationThreshold", evaluationThreshold,
                        "recommendedThreshold", recommendedThreshold,
                        "modelVersion", first.modelVersion()));
        return repository.findRuns(actor.tenantId()).stream()
                .filter(run -> run.id().equals(runId)).findFirst().orElseThrow();
    }

    private Map<String, EvaluationRun.CategoryMetrics> categoryMetrics(List<SampleInference> inferences) {
        Map<String, EvaluationRun.CategoryMetrics> result = new LinkedHashMap<>();
        for (EvaluationSample.MediaCategory category : EvaluationSample.MediaCategory.values()) {
            List<ScoredLabel> values = inferences.stream()
                    .filter(item -> item.sample().mediaCategory() == category)
                    .map(item -> new ScoredLabel(
                            item.sample().groundTruth(), item.inference().syntheticProbability()))
                    .toList();
            if (!values.isEmpty() && DetectionMetricsCalculator.hasBothClasses(values)) {
                double threshold = DetectionMetricsCalculator.recommendThreshold(values);
                result.put(category.name(), new EvaluationRun.CategoryMetrics(
                        threshold, DetectionMetricsCalculator.calculate(values, threshold)));
            }
        }
        return Map.copyOf(result);
    }

    private record SampleInference(EvaluationSample sample, AideInferenceClient.Inference inference) {}
}
