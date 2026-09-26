import { apiRequest } from './http'
import type {
  AgentEvaluationBaseline, AgentEvaluationBatchSummary, AgentEvaluationCase, AgentEvaluationRun,
} from '../types/agent-evaluation'

export interface CreateAgentEvaluationCaseRequest {
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

export const agentEvaluationApi = {
  baselines: (accessToken: string) =>
    apiRequest<AgentEvaluationBaseline[]>('/agent-evaluations/baselines', {}, accessToken),
  installBaselines: (accessToken: string) =>
    apiRequest<AgentEvaluationCase[]>('/agent-evaluations/baselines/install', {
      method: 'POST',
    }, accessToken),
  cases: (accessToken: string) =>
    apiRequest<AgentEvaluationCase[]>('/agent-evaluations/cases', {}, accessToken),
  createCase: (request: CreateAgentEvaluationCaseRequest, accessToken: string) =>
    apiRequest<AgentEvaluationCase>('/agent-evaluations/cases', {
      method: 'POST', body: JSON.stringify(request),
    }, accessToken),
  deleteCase: (caseId: string, accessToken: string) =>
    apiRequest<void>(`/agent-evaluations/cases/${caseId}`, { method: 'DELETE' }, accessToken),
  runs: (accessToken: string) =>
    apiRequest<AgentEvaluationRun[]>('/agent-evaluations/runs', {}, accessToken),
  evaluate: (evaluationCaseId: string, agentTaskId: string, accessToken: string) =>
    apiRequest<AgentEvaluationRun>('/agent-evaluations/runs', {
      method: 'POST', body: JSON.stringify({ evaluationCaseId, agentTaskId }),
    }, accessToken),
  evaluateBatch: (evaluationCaseId: string, agentTaskIds: string[], accessToken: string) =>
    apiRequest<AgentEvaluationBatchSummary>('/agent-evaluations/runs/batch', {
      method: 'POST', body: JSON.stringify({ evaluationCaseId, agentTaskIds }),
    }, accessToken),
}
