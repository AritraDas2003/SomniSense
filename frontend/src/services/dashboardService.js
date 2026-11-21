import { useMockApi } from './api/apiConfig'
import httpClient from './api/httpClient'
import * as mockMonitoringService from './mocks/mockMonitoringService'

export function getDashboard() {
  if (useMockApi) return mockMonitoringService.getDashboard()
  return httpClient.get('/dashboard/summary')
}
