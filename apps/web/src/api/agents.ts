import type { AgentTask, AgentTaskDetails } from '../types/agent'
import { apiRequest } from './http'

export const agentApi = {
  list(accessToken: string) {
    return apiRequest<AgentTask[]>('/agent-tasks', {}, accessToken)
  },
  get(taskId: string, accessToken: string) {
    return apiRequest<AgentTaskDetails>(`/agent-tasks/${taskId}`, {}, accessToken)
  },
  create(caseId: string, goal: string, stepBudget: number, accessToken: string) {
    return apiRequest<AgentTaskDetails>(
      '/agent-tasks',
      { method: 'POST', body: JSON.stringify({ caseId, goal, stepBudget }) },
      accessToken,
    )
  },
  run(taskId: string, version: number, accessToken: string) {
    return apiRequest<AgentTaskDetails>(
      `/agent-tasks/${taskId}/run`,
      { method: 'POST', body: JSON.stringify({ version }) },
      accessToken,
    )
  },
  cancel(taskId: string, version: number, accessToken: string) {
    return apiRequest<AgentTaskDetails>(
      `/agent-tasks/${taskId}/cancel`,
      { method: 'POST', body: JSON.stringify({ version }) },
      accessToken,
    )
  },
}
