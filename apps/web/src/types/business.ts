export interface MediaAsset {
  id: string
  tenantId: string
  originalFilename: string
  contentType: string
  byteSize: number
  sha256: string
  storageStatus: 'REGISTERED' | 'STORED' | 'QUARANTINED'
  createdBy: string
  createdAt: string
}

export interface RegisterAssetRequest {
  originalFilename: string
  contentType: string
  byteSize: number
  sha256: string
}

export type CasePriority = 'LOW' | 'NORMAL' | 'HIGH' | 'CRITICAL'
export type CaseStatus =
  | 'DRAFT'
  | 'READY'
  | 'INVESTIGATING'
  | 'WAITING_REVIEW'
  | 'CONFIRMED'
  | 'REJECTED'
  | 'FAILED'
  | 'ARCHIVED'

export interface InvestigationCase {
  id: string
  tenantId: string
  caseNumber: string
  title: string
  description: string
  priority: CasePriority
  status: CaseStatus
  createdBy: string
  assignedInvestigatorId: string | null
  assignedReviewerId: string | null
  version: number
  createdAt: string
  updatedAt: string
}

export interface CaseDetails {
  investigationCase: InvestigationCase
  assets: Pick<
    MediaAsset,
    'id' | 'originalFilename' | 'contentType' | 'byteSize' | 'sha256' | 'storageStatus' | 'createdAt'
  >[]
}

export interface CreateCaseRequest {
  title: string
  description: string
  priority: CasePriority
  assetIds: string[]
}

export interface AuditEntry {
  id: string
  actorUserId: string | null
  action: string
  details: Record<string, unknown>
  createdAt: string
}
