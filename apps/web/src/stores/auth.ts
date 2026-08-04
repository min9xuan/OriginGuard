import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { authApi } from '../api/auth'
import type { AuthenticatedUser, LoginRequest } from '../types/auth'

const ACCESS_TOKEN_KEY = 'originguard.access-token'
const USER_KEY = 'originguard.user'

export const useAuthStore = defineStore('auth', () => {
  const accessToken = ref(sessionStorage.getItem(ACCESS_TOKEN_KEY) ?? '')
  const user = ref<AuthenticatedUser | null>(readStoredUser())
  const initialized = ref(false)
  const authenticated = computed(() => Boolean(accessToken.value && user.value))

  async function login(request: LoginRequest) {
    const response = await authApi.login(request)
    setSession(response.accessToken, response.user)
  }

  async function restoreSession() {
    if (initialized.value) return
    try {
      if (accessToken.value) {
        try {
          user.value = await authApi.me(accessToken.value)
          persist()
        } catch {
          clearSession()
          const response = await authApi.refresh()
          setSession(response.accessToken, response.user)
        }
      } else {
        const response = await authApi.refresh()
        setSession(response.accessToken, response.user)
      }
    } catch {
      clearSession()
    } finally {
      initialized.value = true
    }
  }

  async function logout() {
    try {
      await authApi.logout(accessToken.value)
    } finally {
      clearSession()
    }
  }

  function hasPermission(permission: string) {
    return user.value?.permissions.includes(permission) ?? false
  }

  function setSession(token: string, currentUser: AuthenticatedUser) {
    accessToken.value = token
    user.value = currentUser
    initialized.value = true
    persist()
  }

  function persist() {
    sessionStorage.setItem(ACCESS_TOKEN_KEY, accessToken.value)
    if (user.value) sessionStorage.setItem(USER_KEY, JSON.stringify(user.value))
  }

  function clearSession() {
    accessToken.value = ''
    user.value = null
    sessionStorage.removeItem(ACCESS_TOKEN_KEY)
    sessionStorage.removeItem(USER_KEY)
  }

  return { accessToken, user, initialized, authenticated, login, logout, restoreSession, hasPermission }
})

function readStoredUser(): AuthenticatedUser | null {
  const value = sessionStorage.getItem(USER_KEY)
  if (!value) return null
  try {
    return JSON.parse(value) as AuthenticatedUser
  } catch {
    sessionStorage.removeItem(USER_KEY)
    return null
  }
}
