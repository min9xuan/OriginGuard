export type EvaluationGroundTruth = 'AUTHENTIC' | 'SYNTHETIC'
export type EvaluationMediaCategory = 'PHOTOGRAPH' | 'CARTOON' | 'ILLUSTRATION' | 'OTHER'

export interface EvaluationSample {
  id: string
  tenantId: string
  assetId: string
  assetFilename: string
  contentType: string
  groundTruth: EvaluationGroundTruth
  mediaCategory: EvaluationMediaCategory
  generatorName: string
  createdBy: string
  createdAt: string
}

export interface EvaluationMetrics {
  sampleCount: number
  truePositive: number
  trueNegative: number
  falsePositive: number
  falseNegative: number
  accuracy: number
  precision: number
  recall: number
  f1: number
}

export interface EvaluationCategoryMetrics {
  recommendedThreshold: number
  metrics: EvaluationMetrics
}

export interface EvaluationResult {
  id: string
  sampleId: string
  assetId: string
  assetFilename: string
  groundTruth: EvaluationGroundTruth
  syntheticProbability: number
  predictedLabel: EvaluationGroundTruth
  correct: boolean
  processingMilliseconds: number
  qualityStatus: string
}

export interface EvaluationRun {
  id: string
  tenantId: string
  modelCode: string
  modelVersion: string
  evaluationThreshold: number
  recommendedThreshold: number
  metrics: EvaluationMetrics
  categoryMetrics: Record<string, EvaluationCategoryMetrics>
  results: EvaluationResult[]
  createdBy: string
  createdAt: string
}
