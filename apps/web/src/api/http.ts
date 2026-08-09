import type { ApiError } from '../types/auth'

export class ApiRequestError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
  ) {
    super(message)
  }
}

export async function apiRequest<T>(path: string, init: RequestInit = {}, accessToken?: string): Promise<T> {
  const headers = new Headers(init.headers)
  if (init.body && !(init.body instanceof FormData)) headers.set('Content-Type', 'application/json')
  if (accessToken) headers.set('Authorization', `Bearer ${accessToken}`)

  const response = await fetch(`/api/v1${path}`, {
    ...init,
    headers,
    credentials: 'include',
  })
  if (!response.ok) {
    const error = (await response.json().catch(() => ({
      code: 'HTTP_ERROR',
      message: `Request failed with status ${response.status}`,
    }))) as ApiError
    throw new ApiRequestError(response.status, error.code, error.message)
  }
  if (response.status === 204) return undefined as T
  return (await response.json()) as T
}

export async function apiBlobRequest(path: string, accessToken: string): Promise<Blob> {
  const response = await fetch(`/api/v1${path}`, {
    headers: { Authorization: `Bearer ${accessToken}` },
    credentials: 'include',
  })
  if (!response.ok) {
    const error = (await response.json().catch(() => ({
      code: 'HTTP_ERROR',
      message: `Request failed with status ${response.status}`,
    }))) as ApiError
    throw new ApiRequestError(response.status, error.code, error.message)
  }
  return response.blob()
}
