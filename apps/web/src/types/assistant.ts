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
