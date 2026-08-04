import type {
  AuditEntry,
  CaseDetails,
  CasePriority,
  CaseStatus,
  CreateCaseRequest,
  InvestigationCase,
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
}
