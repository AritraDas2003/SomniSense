import { devices, patients, sessions } from './mockStore.js'

const wait = (milliseconds = 300) => new Promise((resolve) => window.setTimeout(resolve, milliseconds))

function toDeviceResponse(device) {
  const assignedPatient = device.assignedPatientId
    ? patients.find((patient) => patient.id === device.assignedPatientId) || null
    : null

  return {
    id: device.id,
    deviceCode: device.deviceCode,
    deviceType: device.deviceType,
    modelName: device.modelName,
    serialNumber: device.serialNumber ?? null,
    active: Boolean(device.isActive),
    enrollmentStatus: device.enrollmentStatus,
    connectivityStatus: device.connectivityStatus,
    lastSeenAt: device.lastSeenAt,
    currentAssignment: assignedPatient
      ? {
          assignmentId: `mock-assign-${device.id}`,
          patientId: assignedPatient.id,
          patientCode: assignedPatient.patientCode,
          assignedAt: device.lastSeenAt,
        }
      : null,
  }
}

export async function listDevices(options = {}) {
  await wait()
  const {
    deviceType = 'ALL',
    enrollmentStatus = 'ALL',
    active,
    connectivity = 'ALL',
    q = '',
    page = 0,
    size = 20,
  } = options

  const queryText = q.trim().toLowerCase()
  const filtered = devices.map(toDeviceResponse).filter((device) => {
    const matchesQuery =
      !queryText
      || `${device.deviceCode} ${device.modelName || ''} ${device.serialNumber || ''}`
        .toLowerCase()
        .includes(queryText)
    const matchesType = !deviceType || deviceType === 'ALL' || device.deviceType === deviceType
    const matchesEnrollment =
      !enrollmentStatus || enrollmentStatus === 'ALL' || device.enrollmentStatus === enrollmentStatus
    const matchesActive = active === undefined || device.active === active
    const matchesConnectivity =
      !connectivity || connectivity === 'ALL' || device.connectivityStatus === connectivity
    return matchesQuery && matchesType && matchesEnrollment && matchesActive && matchesConnectivity
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

export async function assignDevice({ deviceId, patientId, replaceExisting = false }) {
  await wait(420)
  const device = devices.find((candidate) => candidate.id === deviceId)
  const patient = patients.find((candidate) => candidate.id === patientId)

  if (!device || !patient) {
    const error = new Error('The selected device or patient could not be found.')
    error.status = 404
    error.code = 'RESOURCE_NOT_FOUND'
    throw error
  }
  if (!device.isActive || device.enrollmentStatus !== 'ENROLLED') {
    const error = new Error(
      `Device ${device.deviceCode} must be active and ENROLLED to be assigned.`,
    )
    error.status = 409
    error.code = 'DEVICE_NOT_AVAILABLE'
    throw error
  }
  if (device.deviceType !== patient.sourceType) {
    const error = new Error(
      'Device type must match the patient’s source type (SIMULATED with SIMULATED, PHYSICAL with PHYSICAL).',
    )
    error.status = 400
    error.code = 'SOURCE_TYPE_MISMATCH'
    throw error
  }

  if (device.assignedPatientId === patientId) {
    return {
      id: `mock-assign-${device.id}`,
      active: true,
      deviceId: device.id,
      deviceCode: device.deviceCode,
      deviceType: device.deviceType,
      patientId: patient.id,
      patientCode: patient.patientCode,
      patientFullName: patient.fullName,
      assignedAt: new Date().toISOString(),
    }
  }

  const deviceOtherPatient = device.assignedPatientId
    ? patients.find((p) => p.id === device.assignedPatientId)
    : null
  const patientCurrentDevice = devices.find((d) => d.assignedPatientId === patientId) || null

  if ((deviceOtherPatient || patientCurrentDevice) && !replaceExisting) {
    if (deviceOtherPatient) {
      const error = new Error(
        `Device ${device.deviceCode} is already assigned to patient ${deviceOtherPatient.patientCode}. Send replaceExisting=true to reassign it.`,
      )
      error.status = 409
      error.code = 'DEVICE_ALREADY_ASSIGNED'
      throw error
    }
    const error = new Error(
      `Patient ${patient.patientCode} already has device ${patientCurrentDevice.deviceCode}. Send replaceExisting=true to replace it.`,
    )
    error.status = 409
    error.code = 'PATIENT_ALREADY_HAS_DEVICE'
    throw error
  }

  if (
    sessions.some(
      (s) =>
        s.status === 'ACTIVE'
        && ((deviceOtherPatient && s.patientId === deviceOtherPatient.id)
          || (patientCurrentDevice && s.patientId === patient.id)),
    )
  ) {
    const error = new Error('A monitoring session is active on the existing assignment; end the session first.')
    error.status = 409
    error.code = 'SESSION_ACTIVE'
    throw error
  }

  if (patientCurrentDevice) {
    patientCurrentDevice.assignedPatientId = null
  }
  device.assignedPatientId = patientId

  return {
    id: `mock-assign-${device.id}`,
    active: true,
    deviceId: device.id,
    deviceCode: device.deviceCode,
    deviceType: device.deviceType,
    patientId: patient.id,
    patientCode: patient.patientCode,
    patientFullName: patient.fullName,
    assignedAt: new Date().toISOString(),
  }
}
