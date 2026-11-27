import { describe, it, expect, vi, beforeEach } from 'vitest'

const mockHttp = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
}))

vi.mock('../services/api/apiConfig.js', () => ({
  useMockApi: false,
  apiBaseUrl: 'http://localhost:8080/api/v1',
}))

vi.mock('../services/api/httpClient.js', () => ({
  default: mockHttp,
}))

import { getDashboard } from '../services/dashboardService'
import {
  cancelSession,
  endSession,
  getPatientOverview,
  pauseSimulation,
  resumeSimulation,
  startSession,
  startSimulation,
  stopSimulation,
} from '../services/patientService'

describe('LIVE API service integration layer', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('fetches consolidated summary once in dashboardService.getDashboard()', async () => {
    mockHttp.get.mockResolvedValueOnce({
      generatedAt: '2026-11-27T06:00:00Z',
      modelVersion: 'phase7_model_c:1.0.0',
      summary: {
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
      activeSessionsList: [],
      recentPatients: [],
    })

    const res = await getDashboard()
    expect(mockHttp.get).toHaveBeenCalledTimes(1)
    expect(mockHttp.get).toHaveBeenCalledWith('/dashboard/summary')
    expect(res.dataMode).toBe('LIVE')
    expect(res.summary.patientCount).toBe(0)
    expect(res.patients).toEqual([])
  })

  it('loads predictions and handles 404 simulation cleanly in getPatientOverview()', async () => {
    const sim404 = Object.assign(new Error('Not found'), { status: 404 })
    mockHttp.get.mockImplementation((url) => {
      if (url === '/patients/pt-1') {
        return Promise.resolve({
          id: 'pt-1',
          patientCode: 'PT-001',
          sourceType: 'SIMULATED',
          activeSession: { sessionId: 'ses-1', sessionCode: 'SES-1' },
        })
      }
      if (url === '/sessions/ses-1') {
        return Promise.resolve({ id: 'ses-1', status: 'ACTIVE', sourceType: 'SIMULATED' })
      }
      if (url === '/sessions/ses-1/readings/latest') {
        return Promise.resolve({ connectivityStatus: 'CONNECTED', latest: null })
      }
      if (url === '/sessions/ses-1/readings') {
        return Promise.resolve({ content: [], totalPages: 1 })
      }
      if (url === '/sessions/ses-1/predictions/latest') {
        return Promise.resolve({
          sessionId: 'ses-1',
          hasPrediction: true,
          latest: { id: 'pred-1', predictionStatus: 'NEEDS_REVIEW', predictedClass: 'APNEA_EVENT', confidenceScore: 0.91 },
        })
      }
      if (url === '/sessions/ses-1/predictions') {
        return Promise.resolve({
          content: [{ id: 'pred-1', predictionStatus: 'NEEDS_REVIEW', predictedClass: 'APNEA_EVENT', confidenceScore: 0.91 }],
        })
      }
      if (url === '/sessions/ses-1/simulation') {
        return Promise.reject(sim404)
      }
      return Promise.reject(new Error(`Unexpected URL: ${url}`))
    })

    const overview = await getPatientOverview('pt-1')
    expect(overview.mlAvailable).toBe(true)
    expect(overview.simulation).toBeNull()
    expect(overview.latestPrediction.id).toBe('pred-1')
    expect(overview.predictions).toHaveLength(1)
  })

  it('routes session and simulator controls through Spring Boot endpoints', async () => {
    mockHttp.post.mockResolvedValue({ ok: true })

    await startSession({ patientId: 'pt-1', deviceAssignmentId: 'asg-1' })
    expect(mockHttp.post).toHaveBeenCalledWith('/sessions', { patientId: 'pt-1', deviceAssignmentId: 'asg-1' })

    await endSession('ses-1')
    expect(mockHttp.post).toHaveBeenCalledWith('/sessions/ses-1/end')

    await cancelSession('ses-1')
    expect(mockHttp.post).toHaveBeenCalledWith('/sessions/ses-1/cancel')

    await startSimulation('ses-1', { scenarioId: 'severe_osa', seed: '123' })
    expect(mockHttp.post).toHaveBeenCalledWith('/sessions/ses-1/simulation/start', {
      scenarioId: 'severe_osa',
      cadenceHz: 1.0,
      playbackSpeed: 1.0,
      seed: 123,
    })

    await pauseSimulation('ses-1')
    await resumeSimulation('ses-1')
    await stopSimulation('ses-1')
    expect(mockHttp.post).toHaveBeenCalledWith('/sessions/ses-1/simulation/pause')
    expect(mockHttp.post).toHaveBeenCalledWith('/sessions/ses-1/simulation/resume')
    expect(mockHttp.post).toHaveBeenCalledWith('/sessions/ses-1/simulation/stop')
  })
})
