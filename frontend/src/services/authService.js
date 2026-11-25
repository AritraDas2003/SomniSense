import { useMockApi } from './api/apiConfig.js'
import httpClient from './api/httpClient.js'
import * as mockAuthService from './mocks/mockAuthService.js'

export const MOCK_SESSION_STORAGE_KEY = 'somnisense.mock-user'

function mapBackendUser(user) {
  if (!user) return null
  return {
    id: user.id,
    username: user.username,
    email: user.email,
    name: user.username,
    role: user.role,
    active: user.active,
    lastLoginAt: user.lastLoginAt ?? null,
    createdAt: user.createdAt ?? null,
    isMock: false,
  }
}

export async function ensureCsrfToken() {
  if (useMockApi) return null
  return httpClient.get('/auth/csrf')
}

export async function login(credentials) {
  const loginValue = (credentials?.login ?? credentials?.username ?? '').trim()
  const password = credentials?.password ?? ''

  if (useMockApi) {
    const mockUser = await mockAuthService.login({ login: loginValue, password })
    sessionStorage.setItem(MOCK_SESSION_STORAGE_KEY, JSON.stringify(mockUser))
    return mockUser
  }

  await ensureCsrfToken()
  const user = await httpClient.post('/auth/login', {
    login: loginValue,
    password,
  })
  return mapBackendUser(user)
}

export async function getCurrentUser() {
  if (useMockApi) {
    try {
      const storedUser = sessionStorage.getItem(MOCK_SESSION_STORAGE_KEY)
      return storedUser ? JSON.parse(storedUser) : null
    } catch {
      return null
    }
  }

  await ensureCsrfToken()
  const user = await httpClient.get('/auth/me')
  return mapBackendUser(user)
}

export async function logout() {
  if (useMockApi) {
    sessionStorage.removeItem(MOCK_SESSION_STORAGE_KEY)
    return
  }

  try {
    await httpClient.post('/auth/logout')
  } catch (error) {
    // If session already expired or invalidated on server, local sign-out still succeeds.
    if (error?.status !== 401) {
      throw error
    }
  }
}
