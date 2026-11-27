import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { act, fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'

const mockService = vi.hoisted(() => ({
  getPatientOverview: vi.fn(),
  startSession: vi.fn(),
  endSession: vi.fn(),
  cancelSession: vi.fn(),
  startSimulation: vi.fn(),
  pauseSimulation: vi.fn(),
  resumeSimulation: vi.fn(),
  stopSimulation: vi.fn(),
}))

vi.mock('../services/patientService', () => mockService)

vi.mock('recharts', () => ({
  ResponsiveContainer: ({ children }) => <div data-testid="chart-container">{children}</div>,
  LineChart: ({ children }) => <div>{children}</div>,
  CartesianGrid: () => null,
  XAxis: () => null,
  YAxis: () => null,
  Tooltip: () => null,
  Line: () => null,
}))

import PatientDetailsPage from '../pages/patients/PatientDetailsPage'

function buildOverview(overrides = {}) {
  return {
    patient: {
      id: 'pt-1',
      patientCode: 'PT-SIM001',
      fullName: 'Sim Patient One',
      alias: 'Sim One',
      sourceType: 'SIMULATED',
      status: 'ACTIVE',
      currentDevice: {
        assignmentId: 'asg-1',
        deviceId: 'dev-1',
        deviceCode: 'DEV-SIM-01',
        connectivityStatus: 'CONNECTED',
        lastSeenAt: '2026-11-27T05:59:55Z',
      },
      activeSession: {
        sessionId: 'ses-1',
        sessionCode: 'SES-001',
        startedAt: '2026-11-27T05:54:00Z',
      },
    },
    session: {
      id: 'ses-1',
      sessionCode: 'SES-001',
      status: 'ACTIVE',
      sourceType: 'SIMULATED',
      startedAt: '2026-11-27T05:54:00Z',
    },
    latestReadings: {
      connectivityStatus: 'CONNECTED',
      latest: { spo2: 96.4, heartRate: 67, signalQuality: 98, measuredAt: '2026-11-27T05:59:55Z', receivedAt: '2026-11-27T05:59:56Z' },
    },
    readings: [{ spo2: 96.4, heartRate: 67, measuredAt: '2026-11-27T05:59:55Z' }],
    latestPrediction: null,
    predictions: [],
    simulation: null,
    generatedAt: '2026-11-27T06:00:00Z',
    dataMode: 'LIVE',
    mlAvailable: true,
    ...overrides,
  }
}

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/patients/pt-1']}>
      <Routes>
        <Route path="/patients/:patientId" element={<PatientDetailsPage />} />
      </Routes>
    </MemoryRouter>,
  )
}

describe('PatientDetailsPage', () => {
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

  it('shows neutral waiting state without fabricating NORMAL when active session has no prediction', async () => {
    mockService.getPatientOverview.mockResolvedValueOnce(buildOverview())
    renderPage()

    expect(await screen.findByText('Evaluating initial 60s baseline window...')).toBeInTheDocument()
    expect(screen.getByText(/Model output only\. This is not a diagnosis\./)).toBeInTheDocument()
    expect(screen.queryByText('Normal')).not.toBeInTheDocument()
    expect(screen.queryByText('Model service unavailable')).not.toBeInTheDocument()
  })

  it('renders DEGRADED rejection, ERROR state, and COMPLETED/NEEDS_REVIEW history accurately', async () => {
    mockService.getPatientOverview.mockResolvedValueOnce(
      buildOverview({
        latestPrediction: {
          id: 'pred-rejected',
          predictionStatus: 'REJECTED',
          inputQualityStatus: 'DEGRADED',
          predictedClass: null,
          confidenceScore: null,
          windowStart: '2026-11-27T05:58:50Z',
          windowEnd: '2026-11-27T05:59:50Z',
          processedAt: '2026-11-27T05:59:51Z',
          modelVersion: 'phase7_model_c:1.0.0',
          errorMessage: 'Too many dropout samples',
        },
        predictions: [
          {
            id: 'pred-rejected',
            predictionStatus: 'REJECTED',
            inputQualityStatus: 'DEGRADED',
            predictedClass: null,
            confidenceScore: null,
            windowStart: '2026-11-27T05:58:50Z',
            windowEnd: '2026-11-27T05:59:50Z',
            processedAt: '2026-11-27T05:59:51Z',
          },
          {
            id: 'pred-apnea',
            predictionStatus: 'NEEDS_REVIEW',
            inputQualityStatus: 'VALID',
            predictedClass: 'APNEA_EVENT',
            confidenceScore: 0.895,
            windowStart: '2026-11-27T05:57:50Z',
            windowEnd: '2026-11-27T05:58:50Z',
            processedAt: '2026-11-27T05:58:52Z',
          },
        ],
      }),
    )

    renderPage()
    expect(await screen.findByText('Signal quality degraded — inference skipped')).toBeInTheDocument()
    expect(screen.getByText('Window prediction history')).toBeInTheDocument()
    expect(screen.getByText('Apnea event')).toBeInTheDocument()
    expect(screen.getByText('89.5%')).toBeInTheDocument()
  })

  it('drives simulator start/pause/resume, requires cancel confirmation, and stops polling when session ends', async () => {
    vi.useFakeTimers()

    const initial = buildOverview()
    const startedSim = buildOverview({
      simulation: { id: 'sim-1', scenarioId: 'severe_osa', seed: 42, status: 'RUNNING', lastStep: 5, workerGeneration: 1 },
    })
    const endedSession = buildOverview({
      session: { id: 'ses-1', sessionCode: 'SES-001', status: 'ENDED', sourceType: 'SIMULATED' },
      simulation: { id: 'sim-1', scenarioId: 'severe_osa', seed: 42, status: 'STOPPED', lastStep: 30, workerGeneration: 1 },
    })

    mockService.getPatientOverview
      .mockResolvedValueOnce(initial)
      .mockResolvedValueOnce(startedSim)
      .mockResolvedValueOnce(endedSession)

    mockService.startSimulation.mockResolvedValueOnce({ status: 'RUNNING' })
    mockService.endSession.mockResolvedValueOnce({ status: 'ENDED' })

    renderPage()

    await act(async () => {
      await Promise.resolve()
    })

    fireEvent.change(screen.getByLabelText('Simulation scenario'), { target: { value: 'severe_osa' } })
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: /Start simulator/i }))
      await Promise.resolve()
    })

    expect(mockService.startSimulation).toHaveBeenCalledWith('ses-1', { scenarioId: 'severe_osa', seed: '42' })
    expect(screen.getByRole('button', { name: /Pause simulator/i })).toBeInTheDocument()

    // Cancel requires explicit confirmation first
    fireEvent.click(screen.getByRole('button', { name: /Cancel session/i }))
    expect(mockService.cancelSession).not.toHaveBeenCalled()
    expect(screen.getByText('Confirm cancel session?')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /Keep session/i }))

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: /End session/i }))
      await Promise.resolve()
    })

    expect(mockService.endSession).toHaveBeenCalledWith('ses-1')
    expect(mockService.getPatientOverview).toHaveBeenCalledTimes(3)

    // Session is now ENDED — advancing 30s must NOT trigger additional polls
    await act(async () => {
      vi.advanceTimersByTime(30_000)
      await Promise.resolve()
    })
    expect(mockService.getPatientOverview).toHaveBeenCalledTimes(3)
  })
})
