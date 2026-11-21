import { devices, patients, predictionsByPatientId, readingsByPatientId, sessions } from './mockStore'

const wait = (milliseconds = 300) => new Promise((resolve) => window.setTimeout(resolve, milliseconds))
const freshnessFor = (timestamp) => {
  if (!timestamp) return 'MISSING'
  return Date.now() - new Date(timestamp).getTime() > 15 * 60_000 ? 'STALE' : 'FRESH'
}

function getPatientRow(patient) {
  const device = devices.find((candidate) => candidate.assignedPatientId === patient.id)
  const patientReadings = readingsByPatientId[patient.id] || []
  const latestReading = [...patientReadings].reverse().find((reading) => reading.spo2 !== null || reading.heartRate !== null)
  const patientSession = sessions.find((session) => session.patientId === patient.id && session.status === 'ACTIVE')
  const lastReadingAt = latestReading?.measuredAt || null

  return {
    ...patient,
    device,
    session: patientSession || null,
    latestReading: latestReading || null,
    lastReadingAt,
    freshness: freshnessFor(lastReadingAt),
    reviewStatus: predictionsByPatientId[patient.id]?.[0]?.predictionStatus || 'NONE',
  }
}

export async function getDashboard() {
  await wait()
  const patientRows = patients.map(getPatientRow)
  const dataIssues = patientRows.filter((patient) =>
    patient.device && (patient.device.connectivityStatus === 'DISCONNECTED' || patient.freshness === 'STALE'),
  ).length

  return {
    summary: {
      patientCount: patients.length,
      activeSessions: sessions.filter((session) => session.status === 'ACTIVE').length,
      itemsNeedingReview: Object.values(predictionsByPatientId).flat()
        .filter((prediction) => prediction.predictionStatus === 'NEEDS_REVIEW').length,
      dataIssues,
    },
    patients: patientRows,
    dataMode: 'MOCK',
  }
}

export async function getPatients() {
  await wait()
  return patients.map(getPatientRow)
}

export async function getPatientOverview(patientId) {
  await wait(240)
  const patient = patients.find((candidate) => candidate.id === patientId)
  if (!patient) {
    const error = new Error('Patient could not be found.')
    error.status = 404
    throw error
  }

  const device = devices.find((candidate) => candidate.assignedPatientId === patientId) || null
  const patientSession = sessions.find((session) => session.patientId === patientId && session.status === 'ACTIVE') || null
  const readings = readingsByPatientId[patientId] || []
  const latestReading = [...readings].reverse().find((reading) => reading.spo2 !== null || reading.heartRate !== null) || null

  return {
    patient,
    device,
    session: patientSession,
    readings,
    predictions: predictionsByPatientId[patientId] || [],
    freshness: freshnessFor(latestReading?.measuredAt),
    dataMode: 'MOCK',
    mlAvailable: true,
  }
}
