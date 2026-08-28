import type { AgentTask, AgentTaskDetails } from '../types/agent'
import { apiBlobRequest, apiRequest } from './http'

export const agentApi = {
  list(accessToken: string) {
    return apiRequest<AgentTask[]>('/agent-tasks', {}, accessToken)
  },
  get(taskId: string, accessToken: string) {
    return apiRequest<AgentTaskDetails>(`/agent-tasks/${taskId}`, {}, accessToken)
  },
  remove(taskId: string, accessToken: string) {
    return apiRequest<void>(`/agent-tasks/${taskId}`, { method: 'DELETE' }, accessToken)
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
  async events(
    taskId: string,
    accessToken: string,
    onProgress: (event: Record<string, unknown>) => void | Promise<void>,
    signal?: AbortSignal,
  ) {
    const response = await fetch(`/api/v1/agent-tasks/${taskId}/events`, {
      headers: { Authorization: `Bearer ${accessToken}`, Accept: 'text/event-stream' },
      credentials: 'include', signal,
    })
    if (!response.ok || !response.body) throw new Error(`SSE connection failed: ${response.status}`)
    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    while (true) {
      const { value, done } = await reader.read()
      if (done) return
      buffer += decoder.decode(value, { stream: true })
      const frames = buffer.split(/\r?\n\r?\n/)
      buffer = frames.pop() ?? ''
      for (const frame of frames) {
        if (frame.includes('event:progress')) {
          const data = frame.split(/\r?\n/).find(line => line.startsWith('data:'))?.slice(5).trim()
          await onProgress(data ? JSON.parse(data) as Record<string, unknown> : {})
        }
      }
    }
  },
  artifact(taskId: string, observationId: string, artifactId: string, accessToken: string) {
    return apiBlobRequest(
      `/agent-tasks/${taskId}/observations/${observationId}/artifacts/${artifactId}`,
      accessToken,
    )
  },
}
