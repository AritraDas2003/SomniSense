import { devices, patients, predictionsByPatientId, readingsByPatientId, sessions } from './mockStore.js'

const wait = (milliseconds = 300) => new Promise((resolve) => window.setTimeout(resolve, milliseconds))

function buildCurrentDevice(device) {
  if (!device) return null
  return {
    assignmentId: `mock-assign-${device.id}`,
    deviceId: device.id,
    deviceCode: device.deviceCode,
    connectivityStatus: device.connectivityStatus,
    lastSeenAt: device.lastSeenAt,
    assignedAt: device.lastSeenAt,
  }
}

function buildActiveSession(session) {
  if (!session) return null
  return {
    sessionId: session.id,
    sessionCode: session.sessionCode,
    startedAt: session.startedAt,
  }
}

function buildLatestReading(reading) {
  if (!reading) return null
  return {
    spo2: reading.spo2,
    heartRate: reading.heartRate,
    measuredAt: reading.measuredAt,
  }
}

export function getPatientRow(patient) {
  const device = devices.find((candidate) => candidate.assignedPatientId === patient.id) || null
  const patientReadings = readingsByPatientId[patient.id] || []
  const latestRaw = patientReadings.length ? patientReadings[patientReadings.length - 1] : null
  const patientSession =
    sessions.find((session) => session.patientId === patient.id && session.status === 'ACTIVE') || null

  return {
    ...patient,
    currentDevice: buildCurrentDevice(device),
    activeSession: buildActiveSession(patientSession),
    latestReading: buildLatestReading(latestRaw),
    reviewStatus: predictionsByPatientId[patient.id]?.[0]?.predictionStatus || 'NONE',
  }
}

export async function getDashboard() {
  await wait()
  const patientRows = patients.map(getPatientRow)
  const dataIssues = devices.filter(
    (device) =>
      device.isActive
      && (device.connectivityStatus === 'DISCONNECTED' || device.connectivityStatus === 'STALE'),
  ).length

  return {
    summary: {
      patientCount: patients.length,
      activeSessions: sessions.filter((session) => session.status === 'ACTIVE').length,
      itemsNeedingReview: Object.values(predictionsByPatientId)
        .flat()
        .filter((prediction) => prediction.predictionStatus === 'NEEDS_REVIEW').length,
      dataIssues,
    },
    patients: patientRows.slice(0, 4),
    dataMode: 'MOCK',
  }
}

export async function getPatients(options = {}) {
  await wait()
  const {
    q = '',
    sourceType = 'ALL',
    status = 'ALL',
    page = 0,
    size = 20,
  } = options

  const queryText = q.trim().toLowerCase()
  const filtered = patients.map(getPatientRow).filter((patient) => {
    const haystack = `${patient.patientCode} ${patient.fullName} ${patient.alias || ''}`.toLowerCase()
    const matchesQuery = !queryText || haystack.includes(queryText)
    const matchesSource = !sourceType || sourceType === 'ALL' || patient.sourceType === sourceType
    const matchesStatus = !status || status === 'ALL' || patient.status === status
    return matchesQuery && matchesSource && matchesStatus
  })

  const totalElements = filtered.length
  const totalPages = Math.max(1, Math.ceil(totalElements / size))
  const safePage = Math.min(Math.max(0, page), totalPages - 1)
  const content = filtered.slice(safePage * size, (safePage + 1) * size)

  return {
    content,
    page: safePage,
    size,
    totalElements,
    totalPages: totalElements === 0 ? 0 : totalPages,
  }
}

export async function getPatientOverview(patientId) {
  await wait(240)
  const patient = patients.find((candidate) => candidate.id === patientId)
  if (!patient) {
    const error = new Error('Patient could not be found.')
    error.status = 404
    error.code = 'RESOURCE_NOT_FOUND'
    throw error
  }

  const device = devices.find((candidate) => candidate.assignedPatientId === patientId) || null
  const patientSession =
    sessions.find((session) => session.patientId === patientId && session.status === 'ACTIVE') || null
  const readings = readingsByPatientId[patientId] || []
  const latestRaw = readings.length ? readings[readings.length - 1] : null
  const latestSpo2Row = [...readings].reverse().find((r) => r.spo2 !== null) || null
  const latestHrRow = [...readings].reverse().find((r) => r.heartRate !== null) || null

  const latestReadings = patientSession
    ? {
        sessionId: patientSession.id,
        sessionStatus: patientSession.status,
        sourceType: device?.deviceType || patient.sourceType,
        connectivityStatus: device?.connectivityStatus || 'UNKNOWN',
        deviceLastSeenAt: device?.lastSeenAt || null,
        latest: latestRaw,
        lastSpo2: latestSpo2Row
          ? { value: latestSpo2Row.spo2, measuredAt: latestSpo2Row.measuredAt }
          : null,
        lastHeartRate: latestHrRow
          ? { value: latestHrRow.heartRate, measuredAt: latestHrRow.measuredAt }
          : null,
      }
    : null

  return {
    patient: {
      ...patient,
      currentDevice: buildCurrentDevice(device),
      activeSession: buildActiveSession(patientSession),
      latestReading: buildLatestReading(latestRaw),
    },
    session: patientSession
      ? {
          ...patientSession,
          sourceType: device?.deviceType || patient.sourceType,
          deviceCode: device?.deviceCode || null,
        }
      : null,
    latestReadings,
    readings,
    predictions: predictionsByPatientId[patientId] || [],
    dataMode: 'MOCK',
    mlAvailable: true,
  }
}
