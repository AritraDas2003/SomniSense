import { useMockApi } from './api/apiConfig'
import httpClient from './api/httpClient'
import * as mockAuthService from './mocks/mockAuthService'

export function login(credentials) {
  if (useMockApi) return mockAuthService.login(credentials)
  return httpClient.post('/auth/login', credentials)
}
