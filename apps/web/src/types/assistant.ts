export interface AssistantConversation {
  id: string
  tenantId: string
  userId: string
  title: string
  createdAt: string
  updatedAt: string
}

export interface AssistantGroundingSource {
  label: string
  title: string
  url?: string
  quote?: string
  snippet?: string
  provider?: string
  score?: number
  documentVersion?: number
  venue?: string
  publicationYear?: number
  qualityTier?: string
  qualityReason?: string
}

export interface AssistantMessage {
  id: string
  tenantId: string
  conversationId: string
  role: 'USER' | 'ASSISTANT'
  messageType: 'CHAT' | 'AGENT_REQUEST' | 'AGENT_RESULT' | 'ATTACHMENT_REQUIRED' | 'ERROR'
  content: string
  assetId: string | null
  agentTaskId: string | null
  grounding: Record<string, unknown>
  createdAt: string
}

export interface AssistantConversationDetails {
  conversation: AssistantConversation
  messages: AssistantMessage[]
}

export interface WebSecurityRiskSignal {
  code: string
  severity: 'LOW' | 'MEDIUM' | 'HIGH'
  points: number
  message: string
}

export interface WebSecurityTraceEvent {
  stage: string
  status: string
  summary: string
}

export interface WebSecurityInvestigationReport {
  targetUrl: string
  host: string
  scheme: string
  port: number
  resolvedAddresses: string[]
  riskScore: number
  riskLevel: 'LOW' | 'MEDIUM' | 'HIGH'
  signals: WebSecurityRiskSignal[]
  trace: WebSecurityTraceEvent[]
  tls: Record<string, unknown>
  threatIntelProvider: string
  threatIntelSourceCount: number
  retrievalAffectedScore: boolean
  durationMilliseconds: number
  limitations: string[]
}
