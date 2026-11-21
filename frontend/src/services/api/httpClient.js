import axios from 'axios'
import { apiBaseUrl } from './apiConfig'

const httpClient = axios.create({
  baseURL: apiBaseUrl,
  timeout: 10000,
  headers: { 'Content-Type': 'application/json' },
})

httpClient.interceptors.response.use(
  (response) => response.data,
  (error) => {
    if (error.response?.status === 401 && !error.config?.url?.includes('/auth/login')) {
      window.dispatchEvent(new Event('somnisense:session-expired'))
    }
    const apiError = new Error(
      error.response?.data?.message || error.message || 'The server request failed.',
    )
    apiError.status = error.response?.status
    apiError.code = error.code
    return Promise.reject(apiError)
  },
)

export default httpClient
