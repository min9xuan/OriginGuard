import type {
  AuditEntry,
  AssignableUser,
  CaseDetails,
  CaseWorkflow,
  CasePriority,
  CaseStatus,
  CreateCaseRequest,
  EvidenceConclusion,
  EvidenceConfidence,
  InvestigationEvidence,
  InvestigationCase,
  ReviewStatus,
} from '../types/business'
import { apiRequest } from './http'

export const caseApi = {
  list(accessToken: string) {
    return apiRequest<InvestigationCase[]>('/cases', {}, accessToken)
  },
  get(caseId: string, accessToken: string) {
    return apiRequest<CaseDetails>(`/cases/${caseId}`, {}, accessToken)
  },
  create(request: CreateCaseRequest, accessToken: string) {
    return apiRequest<CaseDetails>(
      '/cases',
      { method: 'POST', body: JSON.stringify(request) },
      accessToken,
    )
  },
  update(
    caseId: string,
    request: { title: string; description: string; priority: CasePriority; version: number },
    accessToken: string,
  ) {
    return apiRequest<CaseDetails>(
      `/cases/${caseId}`,
      { method: 'PATCH', body: JSON.stringify(request) },
      accessToken,
    )
  },
  linkAsset(caseId: string, assetId: string, version: number, accessToken: string) {
    return apiRequest<CaseDetails>(
      `/cases/${caseId}/assets`,
      { method: 'POST', body: JSON.stringify({ assetId, version }) },
      accessToken,
    )
  },
  transition(caseId: string, targetStatus: CaseStatus, version: number, accessToken: string) {
    return apiRequest<CaseDetails>(
      `/cases/${caseId}/transitions`,
      { method: 'POST', body: JSON.stringify({ targetStatus, version }) },
      accessToken,
    )
  },
  audit(caseId: string, accessToken: string) {
    return apiRequest<AuditEntry[]>(`/cases/${caseId}/audit`, {}, accessToken)
  },
  assignees(accessToken: string) {
    return apiRequest<AssignableUser[]>('/cases/assignees', {}, accessToken)
  },
  assign(
    caseId: string,
    request: { investigatorId: string; reviewerId: string; version: number },
    accessToken: string,
  ) {
    return apiRequest<InvestigationCase>(
      `/cases/${caseId}/assignment`,
      { method: 'POST', body: JSON.stringify(request) },
      accessToken,
    )
  },
  workflow(caseId: string, accessToken: string) {
    return apiRequest<CaseWorkflow>(`/cases/${caseId}/workflow`, {}, accessToken)
  },
  addEvidence(
    caseId: string,
    request: {
      assetId: string
      title: string
      observation: string
      conclusion: EvidenceConclusion
      confidence: EvidenceConfidence
      version: number
    },
    accessToken: string,
  ) {
    return apiRequest<InvestigationEvidence>(
      `/cases/${caseId}/evidence`,
      { method: 'POST', body: JSON.stringify(request) },
      accessToken,
    )
  },
  promoteAgentObservation(
    caseId: string,
    observationId: string,
    version: number,
    accessToken: string,
  ) {
    return apiRequest<InvestigationEvidence>(
      `/cases/${caseId}/evidence/from-agent`,
      { method: 'POST', body: JSON.stringify({ observationId, version }) },
      accessToken,
    )
  },
  decideReview(
    caseId: string,
    taskId: string,
    request: {
      decision: ReviewStatus
      reason: string
      citedEvidenceIds: string[]
      taskVersion: number
      caseVersion: number
    },
    accessToken: string,
  ) {
    return apiRequest<CaseWorkflow>(
      `/cases/${caseId}/reviews/${taskId}/decision`,
      { method: 'POST', body: JSON.stringify(request) },
      accessToken,
    )
  },
}
