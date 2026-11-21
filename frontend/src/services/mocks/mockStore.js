const now = Date.now()
const minutesAgo = (minutes) => new Date(now - minutes * 60_000).toISOString()

export const patients = [
  {
    id: 'patient-001',
    patientCode: 'SS-2401',
    fullName: 'Avery Johnson',
    alias: 'Avery J.',
    sourceType: 'SIMULATED',
    status: 'ACTIVE',
    createdAt: minutesAgo(60 * 24 * 5),
  },
  {
    id: 'patient-002',
    patientCode: 'SS-2402',
    fullName: 'Jordan Williams',
    alias: 'Jordan W.',
    sourceType: 'PHYSICAL',
    status: 'ACTIVE',
    createdAt: minutesAgo(60 * 24 * 3),
  },
  {
    id: 'patient-003',
    patientCode: 'SS-2403',
    fullName: 'Riley Chen',
    alias: 'Riley C.',
    sourceType: 'SIMULATED',
    status: 'ACTIVE',
    createdAt: minutesAgo(60 * 24 * 2),
  },
]

export const devices = [
  {
    id: 'device-001',
    deviceCode: 'SIM-OX-001',
    modelName: 'SomniSense OX · Demo',
    deviceType: 'SIMULATED',
    connectivityStatus: 'CONNECTED',
    enrollmentStatus: 'ENROLLED',
    isActive: true,
    lastSeenAt: minutesAgo(2),
    assignedPatientId: 'patient-001',
  },
  {
    id: 'device-002',
    deviceCode: 'PHY-OX-014',
    modelName: 'Pulse oximeter · Series A',
    deviceType: 'PHYSICAL',
    connectivityStatus: 'DISCONNECTED',
    enrollmentStatus: 'ENROLLED',
    isActive: true,
    lastSeenAt: minutesAgo(65),
    assignedPatientId: 'patient-002',
  },
  {
    id: 'device-003',
    deviceCode: 'SIM-OX-003',
    modelName: 'SomniSense OX · Demo',
    deviceType: 'SIMULATED',
    connectivityStatus: 'CONNECTED',
    enrollmentStatus: 'ENROLLED',
    isActive: true,
    lastSeenAt: minutesAgo(38),
    assignedPatientId: 'patient-003',
  },
  {
    id: 'device-004',
    deviceCode: 'SIM-OX-004',
    modelName: 'SomniSense OX · Demo',
    deviceType: 'SIMULATED',
    connectivityStatus: 'CONNECTED',
    enrollmentStatus: 'ENROLLED',
    isActive: true,
    lastSeenAt: minutesAgo(1),
    assignedPatientId: null,
  },
]

export const sessions = [
  {
    id: 'session-001',
    sessionCode: 'SES-2401-A',
    patientId: 'patient-001',
    deviceId: 'device-001',
    status: 'ACTIVE',
    startedAt: minutesAgo(42),
    endedAt: null,
  },
  {
    id: 'session-002',
    sessionCode: 'SES-2403-A',
    patientId: 'patient-003',
    deviceId: 'device-003',
    status: 'ACTIVE',
    startedAt: minutesAgo(85),
    endedAt: null,
  },
]

const samplePoints = [
  [42, 97, 64],
  [37, 96, 66],
  [32, 96, 65],
  [27, 95, 67],
  [22, null, null],
  [17, 96, 63],
  [12, 97, 62],
  [7, 96, 64],
  [2, 97, 65],
]

export const readingsByPatientId = {
  'patient-001': samplePoints.map(([minutes, spo2, heartRate], index) => ({
    id: `reading-${index + 1}`,
    measuredAt: minutesAgo(minutes),
    receivedAt: minutesAgo(minutes),
    spo2,
    heartRate,
    signalQuality: spo2 === null ? null : 94,
    sourceType: 'SIMULATED',
  })),
  'patient-003': [
    { id: 'reading-stale-1', measuredAt: minutesAgo(58), receivedAt: minutesAgo(56), spo2: 96, heartRate: 62, signalQuality: 90, sourceType: 'SIMULATED' },
    { id: 'reading-stale-2', measuredAt: minutesAgo(46), receivedAt: minutesAgo(45), spo2: 95, heartRate: 64, signalQuality: 88, sourceType: 'SIMULATED' },
  ],
}

export const predictionsByPatientId = {
  'patient-001': [
    {
      id: 'prediction-001',
      predictedClass: 'POSSIBLE_EVENT',
      predictionStatus: 'NEEDS_REVIEW',
      processedAt: minutesAgo(8),
      modelVersion: 'demo-model-0.1',
    },
  ],
}

let nextPatientNumber = 2404

export function createPatientRecord(input) {
  const patient = {
    id: `patient-${Date.now()}`,
    patientCode: `SS-${nextPatientNumber++}`,
    fullName: input.fullName.trim(),
    alias: input.alias?.trim() || input.fullName.trim(),
    sourceType: input.sourceType,
    status: 'ACTIVE',
    createdAt: new Date().toISOString(),
  }
  patients.unshift(patient)
  return patient
}
