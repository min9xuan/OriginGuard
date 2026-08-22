import { apiRequest } from './http'
import type {
  EvaluationGroundTruth,
  EvaluationMediaCategory,
  EvaluationRun,
  EvaluationSample,
} from '../types/evaluation'

export const evaluationApi = {
  samples: (accessToken: string) =>
    apiRequest<EvaluationSample[]>('/detection-evaluations/samples', {}, accessToken),
  addSample(
    request: {
      assetId: string
      groundTruth: EvaluationGroundTruth
      mediaCategory: EvaluationMediaCategory
      generatorName: string
    },
    accessToken: string,
  ) {
    return apiRequest<EvaluationSample>('/detection-evaluations/samples', {
      method: 'POST', body: JSON.stringify(request),
    }, accessToken)
  },
  deleteSample: (sampleId: string, accessToken: string) =>
    apiRequest<void>(`/detection-evaluations/samples/${sampleId}`, { method: 'DELETE' }, accessToken),
  runs: (accessToken: string) =>
    apiRequest<EvaluationRun[]>('/detection-evaluations/runs', {}, accessToken),
  run: (evaluationThreshold: number, accessToken: string) =>
    apiRequest<EvaluationRun>('/detection-evaluations/runs', {
      method: 'POST', body: JSON.stringify({ evaluationThreshold }),
    }, accessToken),
}
