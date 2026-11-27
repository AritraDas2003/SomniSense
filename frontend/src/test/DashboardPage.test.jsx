import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { act, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'

const mockGetDashboard = vi.fn()

vi.mock('../services/dashboardService', () => ({
  getDashboard: (...args) => mockGetDashboard(...args),
}))

import DashboardPage from '../pages/dashboard/DashboardPage'

function buildDashboard(overrides = {}) {
  return {
    generatedAt: '2026-11-27T06:00:00Z',
    modelVersion: 'phase7_model_c:1.0.0',
    dataMode: 'LIVE',
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
    patients: [],
    ...overrides,
  }
}

describe('DashboardPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    Object.defineProperty(document, 'visibilityState', {
      configurable: true,
      get: () => 'visible',
    })
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('renders explicit zeroes on empty LIVE snapshot without Stage 1 partial banner', async () => {
    mockGetDashboard.mockResolvedValueOnce(buildDashboard())

    render(
      <MemoryRouter>
        <DashboardPage />
      </MemoryRouter>,
    )

    expect(await screen.findByText('No active monitoring sessions')).toBeInTheDocument()
    expect(screen.queryByText(/Partial overview metrics/i)).not.toBeInTheDocument()
    expect(screen.getAllByText('0').length).toBeGreaterThanOrEqual(4)
  })

  it('displays active sessions, truncation notice, polls every 10s, and handles background poll failures gracefully', async () => {
    vi.useFakeTimers()

    const activeSnapshot = buildDashboard({
      summary: {
        patientCount: 55,
        activeSessions: 55,
        activeSessionsReturned: 50,
        activeSessionsTruncated: true,
        itemsNeedingReview: 2,
        dataIssues: 1,
        activeSessionStatuses: {
          completed: 4,
          needsReview: 3,
          rejected: 1,
          error: 0,
          degradedWindows: 1,
          pendingRetryJobs: 1,
        },
      },
      activeSessionsList: [
        {
          sessionId: 'ses-1',
          sessionCode: 'SES-SIM-01',
          patientId: 'pt-1',
          patientCode: 'PT-SIM001',
          patientDisplayName: 'Sim Alpha',
          sourceType: 'SIMULATED',
          deviceCode: 'DEV-SIM-01',
          connectivityStatus: 'CONNECTED',
          startedAt: '2026-11-27T05:55:00Z',
          simulation: { id: 'sim-1', scenarioId: 'severe_osa', status: 'RUNNING', lastStep: 120, workerGeneration: 1 },
          latestReading: { spo2: 88.5, heartRate: 78, measuredAt: '2026-11-27T05:59:59Z' },
          latestPrediction: {
            id: 'pred-1',
            predictionStatus: 'NEEDS_REVIEW',
            predictedClass: 'APNEA_EVENT',
            confidenceScore: 0.912,
            windowEnd: '2026-11-27T05:59:50Z',
          },
        },
      ],
      patients: [
        {
          id: 'pt-1',
          patientCode: 'PT-SIM001',
          fullName: 'Sim Alpha',
          sourceType: 'SIMULATED',
          status: 'ACTIVE',
          currentDevice: { deviceCode: 'DEV-SIM-01', connectivityStatus: 'CONNECTED' },
          activeSession: { sessionId: 'ses-1', sessionCode: 'SES-SIM-01' },
          latestReading: { spo2: 88.5, heartRate: 78, measuredAt: '2026-11-27T05:59:59Z' },
          reviewStatus: 'NEEDS_REVIEW',
        },
      ],
    })

    mockGetDashboard.mockResolvedValueOnce(activeSnapshot)
    mockGetDashboard.mockRejectedValueOnce(new Error('Transient gateway timeout'))

    render(
      <MemoryRouter>
        <DashboardPage />
      </MemoryRouter>,
    )

    await act(async () => {
      await Promise.resolve()
    })

    expect(screen.getAllByText('Sim Alpha').length).toBeGreaterThanOrEqual(1)
    expect(screen.getByText('Showing newest 50 of 55 active sessions')).toBeInTheDocument()
    expect(screen.getByText('Apnea event')).toBeInTheDocument()
    expect(screen.getByText('91.2%')).toBeInTheDocument()
    expect(mockGetDashboard).toHaveBeenCalledTimes(1)

    await act(async () => {
      vi.advanceTimersByTime(10_000)
      await Promise.resolve()
    })

    expect(mockGetDashboard).toHaveBeenCalledTimes(2)
    expect(screen.getByText(/Live refresh delayed/i)).toBeInTheDocument()
    expect(screen.getByText('Transient gateway timeout')).toBeInTheDocument()
    expect(screen.getAllByText('Sim Alpha').length).toBeGreaterThanOrEqual(1)
  })
})
