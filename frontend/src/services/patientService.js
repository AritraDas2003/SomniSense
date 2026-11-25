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
      predictions: [],
      dataMode: 'LIVE',
      mlAvailable: false,
    }
  }

  const encodedSessionId = encodeURIComponent(targetSessionId)
  const [resolvedSession, latestReadings, firstPage] = await Promise.all([
    session ? Promise.resolve(session) : httpClient.get(`/sessions/${encodedSessionId}`),
    httpClient.get(`/sessions/${encodedSessionId}/readings/latest`),
    httpClient.get(`/sessions/${encodedSessionId}/readings`, {
      params: { page: 0, size: 300 },
    }),
  ])

  let readings = firstPage?.content || []
  if (firstPage && firstPage.totalPages > 1) {
    const lastPage = await httpClient.get(`/sessions/${encodedSessionId}/readings`, {
      params: { page: firstPage.totalPages - 1, size: 300 },
    })
    readings = lastPage?.content || readings
  }

  return {
    patient,
    session: resolvedSession,
    latestReadings,
    readings,
    predictions: [],
    dataMode: 'LIVE',
    mlAvailable: false,
  }
}
