import { useMockApi } from './api/apiConfig.js'
import httpClient from './api/httpClient.js'
import * as mockPatientService from './mocks/mockPatientService.js'
import * as mockMonitoringService from './mocks/mockMonitoringService.js'

function buildQueryParams(options = {}) {
  const params = {}
  if (options.page !== undefined) params.page = options.page
  if (options.size !== undefined) params.size = options.size
  if (options.sort) params.sort = options.sort
  if (options.direction) params.direction = options.direction
  if (options.q && options.q.trim()) params.q = options.q.trim()
  if (options.sourceType && options.sourceType !== 'ALL') params.sourceType = options.sourceType
  if (options.status && options.status !== 'ALL') params.status = options.status
  return params
}

export async function getPatients(options = {}) {
  if (useMockApi) return mockPatientService.listPatients(options)
  return httpClient.get('/patients', { params: buildQueryParams(options) })
}

export async function createPatient(input) {
  const payload = {
    fullName: input.fullName?.trim() ?? '',
    alias: input.alias?.trim() ? input.alias.trim() : null,
    sourceType: input.sourceType,
  }
  if (input.dateOfBirth) payload.dateOfBirth = input.dateOfBirth
  if (input.gender) payload.gender = input.gender
  if (input.contact) payload.contact = input.contact
  if (input.medicalDetails) payload.medicalDetails = input.medicalDetails

  if (useMockApi) return mockPatientService.createPatient(payload)
  return httpClient.post('/patients', payload)
}

async function fetchSimulationSafe(encodedSessionId) {
  try {
    return await httpClient.get(`/sessions/${encodedSessionId}/simulation`)
  } catch (err) {
    if (err?.status === 404) return null
    throw err
  }
}

export async function getPatientOverview(patientId) {
  if (useMockApi) return mockMonitoringService.getPatientOverview(patientId)

  const encodedId = encodeURIComponent(patientId)
  const patient = await httpClient.get(`/patients/${encodedId}`)

  let targetSessionId = patient.activeSession?.sessionId || null
  let session = null

  if (!targetSessionId) {
    const recentSessions = await httpClient.get('/sessions', {
      params: { patientId, page: 0, size: 1 },
    })
    if (recentSessions?.content?.length > 0) {
      session = recentSessions.content[0]
      targetSessionId = session.id
    }
  }

  if (!targetSessionId) {
    return {
      patient,
      session: null,
      latestReadings: null,
      readings: [],
      latestPrediction: null,
      predictions: [],
      simulation: null,
      generatedAt: new Date().toISOString(),
      dataMode: 'LIVE',
      mlAvailable: true,
    }
  }

  const encodedSessionId = encodeURIComponent(targetSessionId)
  const [resolvedSession, latestReadings, firstPage, latestPredResp, predictionsPage, simulation] =
    await Promise.all([
      session ? Promise.resolve(session) : httpClient.get(`/sessions/${encodedSessionId}`),
      httpClient.get(`/sessions/${encodedSessionId}/readings/latest`),
      httpClient.get(`/sessions/${encodedSessionId}/readings`, {
        params: { page: 0, size: 300 },
      }),
      httpClient.get(`/sessions/${encodedSessionId}/predictions/latest`),
      httpClient.get(`/sessions/${encodedSessionId}/predictions`, {
        params: { page: 0, size: 20 },
      }),
      patient.sourceType === 'SIMULATED' ? fetchSimulationSafe(encodedSessionId) : Promise.resolve(null),
    ])

  let readings = firstPage?.content || []
  if (firstPage && firstPage.totalPages > 1) {
    const lastPage = await httpClient.get(`/sessions/${encodedSessionId}/readings`, {
      params: { page: firstPage.totalPages - 1, size: 300 },
    })
    readings = lastPage?.content || readings
  }

  const predictions = predictionsPage?.content || []
  const latestPrediction = latestPredResp?.hasPrediction
    ? latestPredResp.latest
    : predictions[0] || null

  return {
    patient,
    session: resolvedSession,
    latestReadings,
    readings,
    latestPrediction,
    predictions,
    simulation,
    generatedAt: new Date().toISOString(),
    dataMode: 'LIVE',
    mlAvailable: true,
  }
}

export async function startSession({ patientId, deviceAssignmentId = null }) {
  if (useMockApi) throw new Error('Session start is only supported in LIVE API mode.')
  const payload = { patientId }
  if (deviceAssignmentId) payload.deviceAssignmentId = deviceAssignmentId
  return httpClient.post('/sessions', payload)
}

export async function endSession(sessionId) {
  if (useMockApi) throw new Error('Session end is only supported in LIVE API mode.')
  return httpClient.post(`/sessions/${encodeURIComponent(sessionId)}/end`)
}

export async function cancelSession(sessionId) {
  if (useMockApi) throw new Error('Session cancel is only supported in LIVE API mode.')
  return httpClient.post(`/sessions/${encodeURIComponent(sessionId)}/cancel`)
}

export async function startSimulation(sessionId, options = {}) {
  if (useMockApi) throw new Error('Simulator control is only supported in LIVE API mode.')
  const payload = {
    scenarioId: options.scenarioId || 'normal_sleep',
    cadenceHz: options.cadenceHz ?? 1.0,
    playbackSpeed: options.playbackSpeed ?? 1.0,
  }
  if (options.seed !== undefined && options.seed !== '' && options.seed !== null) {
    const parsedSeed = Number(options.seed)
    if (Number.isFinite(parsedSeed)) payload.seed = parsedSeed
  }
  if (options.qualityPreset) payload.qualityPreset = options.qualityPreset
  return httpClient.post(`/sessions/${encodeURIComponent(sessionId)}/simulation/start`, payload)
}

export async function pauseSimulation(sessionId) {
  if (useMockApi) throw new Error('Simulator control is only supported in LIVE API mode.')
  return httpClient.post(`/sessions/${encodeURIComponent(sessionId)}/simulation/pause`)
}

export async function resumeSimulation(sessionId) {
  if (useMockApi) throw new Error('Simulator control is only supported in LIVE API mode.')
  return httpClient.post(`/sessions/${encodeURIComponent(sessionId)}/simulation/resume`)
}

export async function stopSimulation(sessionId) {
  if (useMockApi) throw new Error('Simulator control is only supported in LIVE API mode.')
  return httpClient.post(`/sessions/${encodeURIComponent(sessionId)}/simulation/stop`)
}
