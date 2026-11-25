import { useMockApi } from './api/apiConfig.js'
import httpClient from './api/httpClient.js'
import * as mockDeviceService from './mocks/mockDeviceService.js'

function buildQueryParams(options = {}) {
  const params = {}
  if (options.page !== undefined) params.page = options.page
  if (options.size !== undefined) params.size = options.size
  if (options.sort) params.sort = options.sort
  if (options.direction) params.direction = options.direction
  if (options.q && options.q.trim()) params.q = options.q.trim()
  if (options.deviceType && options.deviceType !== 'ALL') params.deviceType = options.deviceType
  if (options.enrollmentStatus && options.enrollmentStatus !== 'ALL') {
    params.enrollmentStatus = options.enrollmentStatus
  }
  if (options.active !== undefined) params.active = options.active
  if (options.connectivity && options.connectivity !== 'ALL') {
    params.connectivity = options.connectivity
  }
  return params
}

export async function getDevices(options = {}) {
  if (useMockApi) return mockDeviceService.listDevices(options)
  return httpClient.get('/devices', { params: buildQueryParams(options) })
}

export async function assignDevice({ deviceId, patientId, replaceExisting = false }) {
  const payload = {
    deviceId,
    patientId,
    replaceExisting: Boolean(replaceExisting),
  }
  if (useMockApi) return mockDeviceService.assignDevice(payload)
  return httpClient.post('/device-assignments', payload)
}
