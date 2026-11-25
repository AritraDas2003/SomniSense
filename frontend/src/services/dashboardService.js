import { useMockApi } from './api/apiConfig.js'
import httpClient from './api/httpClient.js'
import { getDevices } from './deviceService.js'
import { getPatients } from './patientService.js'
import * as mockMonitoringService from './mocks/mockMonitoringService.js'

export async function getDashboard() {
  if (useMockApi) return mockMonitoringService.getDashboard()

  // Backend does not expose GET /api/v1/dashboard/summary yet.
  // Use server-wide PageResponse.totalElements counts rather than computing totals from page 0.
  const [patientsPage, activeSessionsPage, staleDevicesPage, disconnectedDevicesPage] =
    await Promise.all([
      getPatients({ page: 0, size: 4, sort: 'createdAt', direction: 'desc' }),
      httpClient.get('/sessions', { params: { status: 'ACTIVE', page: 0, size: 1 } }),
      getDevices({ active: true, connectivity: 'STALE', page: 0, size: 1 }),
      getDevices({ active: true, connectivity: 'DISCONNECTED', page: 0, size: 1 }),
    ])

  const staleCount = staleDevicesPage?.totalElements ?? 0
  const disconnectedCount = disconnectedDevicesPage?.totalElements ?? 0

  return {
    summary: {
      patientCount: patientsPage?.totalElements ?? null,
      activeSessions: activeSessionsPage?.totalElements ?? null,
      itemsNeedingReview: null, // Requires backend alerts/predictions aggregation endpoint
      dataIssues: staleCount + disconnectedCount,
    },
    patients: patientsPage?.content || [],
    dataMode: 'LIVE',
  }
}
