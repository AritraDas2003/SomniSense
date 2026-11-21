import { useMockApi } from './api/apiConfig'
import httpClient from './api/httpClient'
import * as mockDeviceService from './mocks/mockDeviceService'

export function getDevices() {
  if (useMockApi) return mockDeviceService.listDevices()
  return httpClient.get('/devices')
}

export function assignDevice(input) {
  if (useMockApi) return mockDeviceService.assignDevice(input)
  return httpClient.post('/device-assignments', input)
}
