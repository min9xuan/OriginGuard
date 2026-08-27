import type { AssistantConversation, AssistantConversationDetails } from '../types/assistant'
import { apiRequest } from './http'

export const assistantApi = {
  list(accessToken: string) {
    return apiRequest<AssistantConversation[]>('/assistant/conversations', {}, accessToken)
  },
  create(accessToken: string, title = '') {
    return apiRequest<AssistantConversationDetails>(
      '/assistant/conversations',
      { method: 'POST', body: JSON.stringify({ title }) },
      accessToken,
    )
  },
  get(conversationId: string, accessToken: string) {
    return apiRequest<AssistantConversationDetails>(
      `/assistant/conversations/${conversationId}`,
      {},
      accessToken,
    )
  },
  remove(conversationId: string, accessToken: string) {
    return apiRequest<void>(
      `/assistant/conversations/${conversationId}`,
      { method: 'DELETE' },
      accessToken,
    )
  },
  send(conversationId: string, content: string, assetId: string | null, accessToken: string) {
    return apiRequest<AssistantConversationDetails>(
      `/assistant/conversations/${conversationId}/messages`,
      { method: 'POST', body: JSON.stringify({ content, assetId }) },
      accessToken,
    )
  },
}
