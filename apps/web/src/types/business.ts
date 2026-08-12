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

export interface AssignableUser {
  id: string
  username: string
  displayName: string
  role: 'INVESTIGATOR' | 'REVIEWER'
}

export type EvidenceConclusion = 'LIKELY_AUTHENTIC' | 'LIKELY_SYNTHETIC' | 'INCONCLUSIVE'
export type EvidenceConfidence = 'LOW' | 'MEDIUM' | 'HIGH'

export interface InvestigationEvidence {
  id: string
  assetId: string
  evidenceType: 'HUMAN_OBSERVATION' | 'AGENT_OBSERVATION'
  title: string
  observation: string
  conclusion: EvidenceConclusion
  confidence: EvidenceConfidence
  sourceObservationId: string | null
  createdBy: string
  createdAt: string
}

export type ReviewStatus = 'PENDING' | 'APPROVED' | 'REJECTED'

export interface ReviewTask {
  id: string
  reviewerId: string
  status: ReviewStatus
  decisionReason: string
  createdBy: string
  decidedBy: string | null
  citedEvidenceIds: string[]
  version: number
  createdAt: string
  decidedAt: string | null
}

export interface CaseWorkflow {
  evidence: InvestigationEvidence[]
  reviewTasks: ReviewTask[]
  agentEvidenceCandidates: AgentEvidenceCandidate[]
}

export interface AgentEvidenceCandidate {
  observationId: string
  taskId: string
  assetId: string
  evidenceType: 'FILE_INTEGRITY' | 'IMAGE_METADATA' | 'PERCEPTUAL_SIMILARITY'
  summary: string
  payload: Record<string, unknown>
  promotedEvidenceId: string | null
  createdAt: string
}
