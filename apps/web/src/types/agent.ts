export type AgentTaskStatus = 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED'

export interface AgentTask {
  id: string
  tenantId: string
  caseId: string
  createdBy: string
  status: AgentTaskStatus
  goal: string
  selectedSkillCode: string | null
  selectedSkillVersion: string | null
  remainingStepBudget: number
  conclusion: Record<string, unknown>
  failureCode: string | null
  failureMessage: string | null
  checkpointVersion: number
  version: number
  createdAt: string
  startedAt: string | null
  completedAt: string | null
  updatedAt: string
}

export interface AgentStep {
  id: string
  taskId: string
  sequenceNumber: number
  stepType: string
  status: 'SUCCEEDED' | 'FAILED'
  skillCode: string
  toolCode: string
  input: Record<string, unknown>
  output: Record<string, unknown>
  createdAt: string
}

export interface AgentObservation {
  id: string
  taskId: string
  caseId: string
  assetId: string | null
  evidenceType:
    | 'MEDIA_METADATA'
    | 'BASIC_MEDIA_FORENSICS'
    | 'FILE_INTEGRITY'
    | 'IMAGE_METADATA'
    | 'PERCEPTUAL_SIMILARITY'
  summary: string
  payload: Record<string, unknown>
  createdAt: string
}

export interface AgentKnowledgeCitation {
  id: string
  documentId: string
  chunkId: string
  documentTitle: string
  documentType: string
  documentVersion: number
  chunkIndex: number
  quote: string
  semanticScore: number
  keywordScore: number
  hybridScore: number
  citationOrder: number
}

export interface AgentKnowledgeRetrieval {
  id: string
  taskId: string
  caseId: string
  skillCode: string
  toolCode: string
  query: string
  retrievalMode: string
  embeddingProvider: string
  knowledgeAvailable: boolean
  citations: AgentKnowledgeCitation[]
  createdAt: string
}

export interface AgentCheckpoint {
  id: string
  taskId: string
  checkpointVersion: number
  state: Record<string, unknown>
  createdAt: string
}

export interface AgentTaskDetails {
  task: AgentTask
  steps: AgentStep[]
  observations: AgentObservation[]
  knowledgeRetrievals: AgentKnowledgeRetrieval[]
  checkpoints: AgentCheckpoint[]
}
