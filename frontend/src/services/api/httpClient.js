import axios from 'axios'
import { apiBaseUrl } from './apiConfig.js'

const SAFE_ERROR_FALLBACK = 'The server request failed.'

export function formatApiErrorMessage(data, fallbackMessage = SAFE_ERROR_FALLBACK) {
  if (!data || typeof data !== 'object') {
    return fallbackMessage
  }
  const baseMessage = typeof data.message === 'string' && data.message.trim()
    ? data.message.trim()
    : fallbackMessage

  if (Array.isArray(data.fieldErrors) && data.fieldErrors.length > 0) {
    const details = data.fieldErrors
      .filter((item) => item && item.field && item.message)
      .map((item) => `${item.field}: ${item.message}`)
    if (details.length > 0) {
      return `${baseMessage} (${details.join('; ')})`
    }
  }

  return baseMessage
}

export function buildApiError(error) {
  const response = error?.response
  const data = response?.data
  const message = formatApiErrorMessage(data, error?.message || SAFE_ERROR_FALLBACK)
  const apiError = new Error(message)

  apiError.status = response?.status ?? data?.status ?? error?.status ?? null
  apiError.code = data?.code || error?.code || 'INTERNAL_ERROR'
  apiError.apiMessage = data?.message || message
  apiError.path = data?.path || error?.config?.url || null
  apiError.correlationId =
    data?.correlationId || response?.headers?.['x-correlation-id'] || null
  apiError.fieldErrors = Array.isArray(data?.fieldErrors) ? data.fieldErrors : []

  return apiError
}

const httpClient = axios.create({
  baseURL: apiBaseUrl,
  timeout: 10000,
  withCredentials: true,
  withXSRFToken: true,
  xsrfCookieName: 'XSRF-TOKEN',
  xsrfHeaderName: 'X-XSRF-TOKEN',
  headers: { 'Content-Type': 'application/json' },
})

httpClient.interceptors.response.use(
  (response) => response.data,
  async (error) => {
    const originalConfig = error?.config || {}
    const status = error?.response?.status
    const backendCode = error?.response?.data?.code

    // Follow docs/api/AUTH.md: 403 CSRF_TOKEN_INVALID -> fetch /auth/csrf once and retry.
    if (
      status === 403
      && backendCode === 'CSRF_TOKEN_INVALID'
      && !originalConfig._csrfRetried
      && !String(originalConfig.url || '').includes('/auth/csrf')
    ) {
      originalConfig._csrfRetried = true
      await httpClient.get('/auth/csrf')
      return httpClient.request(originalConfig)
    }

    const requestUrl = String(originalConfig.url || '')
    const isAuthLoginOrCsrf =
      requestUrl.includes('/auth/login') || requestUrl.includes('/auth/csrf')
    const isSessionMeBootstrap = requestUrl.includes('/auth/me')

    // Normal unauthenticated GET /auth/me (401 UNAUTHORIZED) on startup is not an expired session.
    // However, 401 SESSION_INVALID (role/status/password changed) or any 401 on normal routes expires session.
    if (
      status === 401
      && !isAuthLoginOrCsrf
      && (!isSessionMeBootstrap || backendCode === 'SESSION_INVALID')
    ) {
      window.dispatchEvent(
        new CustomEvent('somnisense:session-expired', {
          detail: {
            code: backendCode || 'UNAUTHORIZED',
            message: error?.response?.data?.message || null,
          },
        }),
      )
    }

    return Promise.reject(buildApiError(error))
  },
)

export default httpClient
