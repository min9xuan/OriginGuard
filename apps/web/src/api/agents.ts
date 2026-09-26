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
    let lastEventId = 0
    let retryDelay = 500
    while (!signal?.aborted) {
      try {
        const response = await fetch(`/api/v1/agent-tasks/${taskId}/events?lastEventId=${lastEventId}`, {
          headers: {
            Authorization: `Bearer ${accessToken}`,
            Accept: 'text/event-stream',
            ...(lastEventId > 0 ? { 'Last-Event-ID': String(lastEventId) } : {}),
          },
          credentials: 'include', signal,
        })
        if (!response.ok || !response.body) throw new Error(`SSE connection failed: ${response.status}`)
        retryDelay = 500
        const reader = response.body.getReader()
        const decoder = new TextDecoder()
        let buffer = ''
        while (!signal?.aborted) {
          const { value, done } = await reader.read()
          if (done) break
          buffer += decoder.decode(value, { stream: true })
          const frames = buffer.split(/\r?\n\r?\n/)
          buffer = frames.pop() ?? ''
          for (const frame of frames) {
            const lines = frame.split(/\r?\n/)
            const id = lines.find(line => line.startsWith('id:'))?.slice(3).trim()
            if (id && Number.isFinite(Number(id))) lastEventId = Math.max(lastEventId, Number(id))
            if (lines.some(line => line.trim() === 'event:progress')) {
              const data = lines.find(line => line.startsWith('data:'))?.slice(5).trim()
              await onProgress(data ? JSON.parse(data) as Record<string, unknown> : {})
            }
          }
        }
      } catch (error) {
        if (signal?.aborted) return
        await new Promise<void>((resolve, reject) => {
          const timeout = window.setTimeout(resolve, retryDelay)
          signal?.addEventListener('abort', () => {
            window.clearTimeout(timeout)
            reject(new DOMException('Aborted', 'AbortError'))
          }, { once: true })
        })
        retryDelay = Math.min(retryDelay * 2, 10_000)
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
