import { useMockApi } from './api/apiConfig'
import httpClient from './api/httpClient'
import * as mockPatientService from './mocks/mockPatientService'
import * as mockMonitoringService from './mocks/mockMonitoringService'

export function getPatients() {
  if (useMockApi) return mockPatientService.listPatients()
  return httpClient.get('/patients')
}

export function createPatient(input) {
  if (useMockApi) return mockPatientService.createPatient(input)
  return httpClient.post('/patients', input)
}

export function getPatientOverview(patientId) {
  if (useMockApi) return mockMonitoringService.getPatientOverview(patientId)
  return httpClient.get(`/patients/${encodeURIComponent(patientId)}/monitoring`)
}
