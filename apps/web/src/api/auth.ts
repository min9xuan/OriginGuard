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
    return apiRequest<AuthResponse>('/auth/refresh', {
      method: 'POST',
      signal: AbortSignal.timeout(8_000),
    })
  },
  me(accessToken: string) {
    return apiRequest<AuthenticatedUser>('/auth/me', {
      signal: AbortSignal.timeout(8_000),
    }, accessToken)
  },
  logout() {
    // The refresh cookie is the session being revoked. Do not attach a possibly expired
    // access token, otherwise the security filter may reject the request before cookie clearing.
    return apiRequest<void>('/auth/logout', { method: 'POST' })
  },
}
