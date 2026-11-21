import { useCallback, useEffect, useMemo, useState } from 'react'
import { Activity, ArrowRight, Check, Cpu, Link2, Radio } from 'lucide-react'
import { useForm, useWatch } from 'react-hook-form'
import { Link, useSearchParams } from 'react-router-dom'
import PageHeader from '../../components/common/PageHeader'
import { SourceBadge, StatusBadge } from '../../components/common/StatusBadge'
import { useMockApi } from '../../services/api/apiConfig'
import { assignDevice, getDevices } from '../../services/deviceService'
import { getPatients } from '../../services/patientService'

function formatLastSeen(value) {
  if (!value) return 'No connection reported'
  return new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))
}

export default function DevicesPage() {
  const mockMode = useMockApi
  const [searchParams] = useSearchParams()
  const requestedPatientId = searchParams.get('patientId') || ''
  const [devices, setDevices] = useState([])
  const [patients, setPatients] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [assignment, setAssignment] = useState(null)
  const {
    register,
    handleSubmit: handleFormSubmit,
    setError: setFormError,
    clearErrors,
    setValue,
    control,
    formState: { errors: formErrors, isSubmitting },
  } = useForm({ defaultValues: { patientId: requestedPatientId, deviceId: '' } })
  const selectedPatientId = useWatch({ control, name: 'patientId' })
  const selectedDeviceId = useWatch({ control, name: 'deviceId' })

  const loadData = useCallback(async () => {
    setLoading(true)
    setError('')
    try {
      const [deviceRows, patientRows] = await Promise.all([getDevices(), getPatients()])
      setDevices(deviceRows)
      setPatients(patientRows)
    } catch (loadError) {
      setError(loadError.message || 'Device inventory could not be loaded.')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    let active = true
    Promise.all([getDevices(), getPatients()])
      .then(([deviceRows, patientRows]) => {
        if (active) {
          setDevices(deviceRows)
          setPatients(patientRows)
        }
      })
      .catch((loadError) => { if (active) setError(loadError.message || 'Device inventory could not be loaded.') })
      .finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [])

  useEffect(() => {
    setValue('patientId', requestedPatientId)
  }, [requestedPatientId, setValue])

  const availableDevices = useMemo(() => devices.filter((device) => device.isActive && !device.assignedPatientId), [devices])

  async function handleAssign(values) {
    clearErrors('root.server')
    try {
      const result = await assignDevice(values)
      setAssignment(result)
      setValue('deviceId', '')
      await loadData()
    } catch (assignError) {
      setFormError('root.server', {
        type: 'server',
        message: assignError.message || 'The device could not be assigned.',
      })
    }
  }

  return (
    <div className="page-stack">
      <PageHeader
        eyebrow="DEVICE MANAGEMENT"
        title="Devices"
        description="Review enrollment and connectivity, then assign an available device to a patient."
      />
      {assignment && (
        <div className="success-inline" role="status">
          <Check size={18} />
          <div><strong>{assignment.device.deviceCode} assigned</strong><span>{mockMode ? 'Assignment is recorded in mock data only.' : 'The configured service confirmed this assignment.'}</span></div>
          <Link className="text-link" to={`/patients/${assignment.patient.id}`}>Open patient <ArrowRight size={15} /></Link>
        </div>
      )}
      {error && <div className="error-state" role="alert"><div><strong>Device workflow could not be completed</strong><p>{error}</p></div><button className="button button-outline" onClick={loadData}>Refresh inventory</button></div>}

      <div className="device-page-grid">
        <section className="panel assign-panel">
          <div className="panel-heading"><div><span className="section-kicker">ASSIGNMENT</span><h2>Connect a device</h2></div><span className="panel-heading-icon"><Link2 size={18} /></span></div>
          <p>Only active, unassigned devices are available. Backend validation is required for real assignments.</p>
          <form onSubmit={handleFormSubmit(handleAssign)} className="assign-form" noValidate>
            <label className="form-field">
              <span>Patient <b>*</b></span>
              <select
                className="select-control form-select"
                aria-invalid={Boolean(formErrors.patientId)}
                aria-describedby={formErrors.patientId ? 'assign-patient-error' : undefined}
                {...register('patientId', { required: 'Select a patient.' })}
              >
                <option value="">Select a patient</option>
                {patients.map((patient) => <option key={patient.id} value={patient.id}>{patient.patientCode} · {patient.alias || patient.fullName}</option>)}
              </select>
              {formErrors.patientId && <small id="assign-patient-error" className="field-error" role="alert">{formErrors.patientId.message}</small>}
            </label>
            <label className="form-field">
              <span>Available device <b>*</b></span>
              <select
                className="select-control form-select"
                aria-invalid={Boolean(formErrors.deviceId)}
                aria-describedby={formErrors.deviceId ? 'assign-device-error' : undefined}
                {...register('deviceId', { required: 'Select an available device.' })}
                disabled={loading || availableDevices.length === 0}
              >
                <option value="">{availableDevices.length ? 'Select a device' : 'No unassigned devices'}</option>
                {availableDevices.map((device) => <option key={device.id} value={device.id}>{device.deviceCode} · {device.deviceType}</option>)}
              </select>
              {!availableDevices.length && !loading && <small className="field-help">All active devices are assigned. Release an assignment through the backend before reuse.</small>}
              {formErrors.deviceId && <small id="assign-device-error" className="field-error" role="alert">{formErrors.deviceId.message}</small>}
            </label>
            <button type="submit" className="button button-primary assign-submit" disabled={isSubmitting || loading || !availableDevices.length || !selectedPatientId || !selectedDeviceId}>
              {isSubmitting ? <><span className="spinner" /> Assigning...</> : <>Assign device <ArrowRight size={16} /></>}
            </button>
            {formErrors.root?.server && <div className="form-error assign-form-error" role="alert">{formErrors.root.server.message}</div>}
          </form>
        </section>

        <section className="panel device-note-panel">
          <div className="device-note-icon"><Radio size={19} /></div>
          <span className="section-kicker">CONNECTION STATUS</span>
          <h2>Online isn't a safety signal.</h2>
          <p>Connected means only that the system reports a connection or recent data arrival. It does not indicate a patient's medical status.</p>
          <div className="device-note-foot"><span className="environment-dot" /> {mockMode ? 'Demo inventory · not live device telemetry' : 'Connectivity status is reported by the configured API'}</div>
        </section>
      </div>

      <section className="panel inventory-panel">
        <div className="table-toolbar">
          <div><h2>Device inventory</h2><p>{loading ? 'Loading inventory...' : `${devices.length} devices registered${mockMode ? ' in mock data' : ''}`}</p></div>
          {mockMode && <span className="mock-pill">MOCK INVENTORY</span>}
        </div>
        {error && loading ? null : (
          <div className="table-scroll">
            <table className="data-table device-table">
              <thead><tr><th>Device</th><th>Type</th><th>Connectivity</th><th>Enrollment</th><th>Assigned patient</th><th>Last seen</th></tr></thead>
              <tbody>
                {devices.map((device) => (
                  <tr key={device.id}>
                    <td><span className="device-code"><span className="device-table-icon"><Cpu size={15} /></span><span><strong>{device.deviceCode}</strong><small>{device.modelName}</small></span></span></td>
                    <td><SourceBadge sourceType={device.deviceType} /></td>
                    <td><StatusBadge status={device.connectivityStatus} /></td>
                    <td><span className="table-main">{device.enrollmentStatus === 'ENROLLED' ? 'Enrolled' : 'Pending'}</span><small className="table-sub">{device.isActive ? 'Active' : 'Inactive'}</small></td>
                    <td>{device.assignedPatient ? <Link className="text-link" to={`/patients/${device.assignedPatient.id}`}>{device.assignedPatient.patientCode}</Link> : <span className="table-sub">Unassigned</span>}</td>
                    <td><span className="table-main">{formatLastSeen(device.lastSeenAt)}</span></td>
                  </tr>
                ))}
                {!loading && devices.length === 0 && <tr><td className="table-empty" colSpan="6">No devices are registered.</td></tr>}
              </tbody>
            </table>
          </div>
        )}
        {loading && <div className="loading-inline"><span className="spinner" /> Loading device inventory...</div>}
      </section>
      <p className="subtle-note"><Activity size={14} /> Device codes identify inventory records; they do not authenticate a sender.</p>
    </div>
  )
}
