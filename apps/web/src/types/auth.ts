export interface AuthenticatedUser {
  id: string
  tenantId: string
  tenantCode: string
  username: string
  displayName: string
  roles: string[]
  permissions: string[]
}

export interface AuthResponse {
  accessToken: string
  tokenType: 'Bearer'
  expiresIn: number
  user: AuthenticatedUser
}

export interface LoginRequest {
  tenantCode: string
  username: string
  password: string
}

export interface ApiError {
  code: string
  message: string
}

