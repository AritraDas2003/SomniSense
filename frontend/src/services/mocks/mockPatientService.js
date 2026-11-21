import { createPatientRecord, patients } from './mockStore'
import { getPatients } from './mockMonitoringService'

const wait = (milliseconds = 320) => new Promise((resolve) => window.setTimeout(resolve, milliseconds))

export async function listPatients() {
  return getPatients()
}

export async function createPatient(input) {
  await wait(450)
  const duplicate = patients.some((patient) =>
    patient.fullName.toLowerCase() === input.fullName.trim().toLowerCase(),
  )

  if (duplicate) {
    const error = new Error('A patient with this name already exists in the demo workspace.')
    error.status = 409
    throw error
  }

  return createPatientRecord(input)
}
