import type { AuthenticatedUser, AuthResponse, LoginRequest } from '../types/auth'
import { apiRequest } from './http'

export const authApi = {
  login(request: LoginRequest) {
    return apiRequest<AuthResponse>('/auth/login', {
      method: 'POST',
      body: JSON.stringify(request),
    })
  },
  refresh() {
    return apiRequest<AuthResponse>('/auth/refresh', { method: 'POST' })
  },
  me(accessToken: string) {
    return apiRequest<AuthenticatedUser>('/auth/me', {}, accessToken)
  },
  logout(accessToken: string) {
    return apiRequest<void>('/auth/logout', { method: 'POST' }, accessToken)
  },
}

