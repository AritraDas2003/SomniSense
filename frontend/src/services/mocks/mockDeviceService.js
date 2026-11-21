import { devices, patients } from './mockStore'

const wait = (milliseconds = 300) => new Promise((resolve) => window.setTimeout(resolve, milliseconds))

export async function listDevices() {
  await wait()
  return devices.map((device) => ({
    ...device,
    assignedPatient: patients.find((patient) => patient.id === device.assignedPatientId) || null,
  }))
}

export async function assignDevice({ deviceId, patientId }) {
  await wait(420)
  const device = devices.find((candidate) => candidate.id === deviceId)
  const patient = patients.find((candidate) => candidate.id === patientId)

  if (!device || !patient) {
    const error = new Error('The selected device or patient could not be found.')
    error.status = 404
    throw error
  }
  if (!device.isActive || device.assignedPatientId) {
    const error = new Error('This device is unavailable. Refresh the inventory and try another device.')
    error.status = 409
    throw error
  }

  device.assignedPatientId = patientId
  return {
    device: { ...device },
    patient: { ...patient },
    assignedAt: new Date().toISOString(),
    dataMode: 'MOCK',
  }
}
