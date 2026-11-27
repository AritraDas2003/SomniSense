import { useMockApi } from './api/apiConfig.js'
import httpClient from './api/httpClient.js'
import * as mockMonitoringService from './mocks/mockMonitoringService.js'

function normalizeMockDashboard(mockData) {
  const summary = mockData?.summary || {}
  const activeSessions = summary.activeSessions ?? 0
  return {
    generatedAt: mockData?.generatedAt || new Date().toISOString(),
    modelVersion: mockData?.modelVersion || 'mock-model-c',
    summary: {
      patientCount: summary.patientCount ?? 0,
      activeSessions,
      activeSessionsReturned: summary.activeSessionsReturned ?? 0,
      activeSessionsTruncated: Boolean(summary.activeSessionsTruncated),
      itemsNeedingReview: summary.itemsNeedingReview ?? 0,
      dataIssues: summary.dataIssues ?? 0,
      activeSessionStatuses: summary.activeSessionStatuses || {
        completed: 0,
        needsReview: summary.itemsNeedingReview ?? 0,
        rejected: 0,
        error: 0,
        degradedWindows: 0,
        pendingRetryJobs: 0,
      },
    },
    activeSessionsList: mockData?.activeSessionsList || [],
    patients: mockData?.patients || [],
    dataMode: 'MOCK',
  }
}

export async function getDashboard() {
  if (useMockApi) {
    return normalizeMockDashboard(await mockMonitoringService.getDashboard())
  }

  const payload = await httpClient.get('/dashboard/summary')
  return {
    generatedAt: payload?.generatedAt || null,
    modelVersion: payload?.modelVersion || null,
    summary: payload?.summary || {
      patientCount: 0,
      activeSessions: 0,
      activeSessionsReturned: 0,
      activeSessionsTruncated: false,
      itemsNeedingReview: 0,
      dataIssues: 0,
      activeSessionStatuses: {
        completed: 0,
        needsReview: 0,
        rejected: 0,
        error: 0,
        degradedWindows: 0,
        pendingRetryJobs: 0,
      },
    },
    activeSessionsList: payload?.activeSessionsList || [],
    patients: payload?.recentPatients || [],
    dataMode: 'LIVE',
  }
}
