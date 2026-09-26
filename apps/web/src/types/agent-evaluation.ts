export interface AgentEvaluationCase {
  id: string
  tenantId: string
  name: string
  description: string
  requiredSkillCodes: string[]
  forbiddenSkillCodes: string[]
  requiredEvidenceTypes: string[]
  forbiddenEvidenceTypes: string[]
  maxToolCalls: number
  maxReplans: number
  maxDurationMilliseconds: number
  minimumScore: number
  requireCompleted: boolean
  requireHumanReview: boolean
  createdBy: string
  createdAt: string
}

export interface AgentEvaluationViolation {
  code: string
  severity: 'WARNING' | 'ERROR' | 'CRITICAL'
  message: string
}

export interface AgentEvaluationRun {
  id: string
  tenantId: string
  evaluationCaseId: string
  evaluationCaseName: string
  agentTaskId: string | null
  agentTaskStatus: string
  totalScore: number
  passed: boolean
  criticalFailure: boolean
  dimensionScores: Record<string, number>
  violations: AgentEvaluationViolation[]
  metrics: Record<string, unknown>
  createdBy: string
  createdAt: string
}

export interface AgentEvaluationBaseline {
  code: string
  name: string
  description: string
  requiredSkillCodes: string[]
  forbiddenSkillCodes: string[]
  requiredEvidenceTypes: string[]
  forbiddenEvidenceTypes: string[]
  maxToolCalls: number
  maxReplans: number
  maxDurationMilliseconds: number
  minimumScore: number
  requireCompleted: boolean
  requireHumanReview: boolean
}

export interface AgentEvaluationBatchSummary {
  runs: AgentEvaluationRun[]
  totalTasks: number
  passedTasks: number
  passRate: number
  averageScore: number
  p95DurationMilliseconds: number
  criticalFailureCount: number
  violationCounts: Record<string, number>
  averageDimensionScores: Record<string, number>
}
