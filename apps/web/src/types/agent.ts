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
  evidenceType: 'MEDIA_METADATA'
  summary: string
  payload: Record<string, unknown>
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
  checkpoints: AgentCheckpoint[]
}
