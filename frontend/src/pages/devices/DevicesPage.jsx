import { useCallback, useEffect, useMemo, useState } from 'react'
import { Activity, AlertTriangle, ArrowRight, Check, Cpu, Link2, Radio } from 'lucide-react'
import { useForm, useWatch } from 'react-hook-form'
import { Link, useSearchParams } from 'react-router-dom'
import PageHeader from '../../components/common/PageHeader'
import { SourceBadge, StatusBadge } from '../../components/common/StatusBadge'
import { useMockApi } from '../../services/api/apiConfig'
import { assignDevice, getDevices } from '../../services/deviceService'
import { getPatients } from '../../services/patientService'

const INVENTORY_PAGE_SIZE = 20

function formatLastSeen(value) {
  if (!value) return 'No connection reported'
  return new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))
}

export default function DevicesPage() {
  const mockMode = useMockApi
  const [searchParams] = useSearchParams()
  const requestedPatientId = searchParams.get('patientId') || ''

  const [devicesPage, setDevicesPage] = useState({
    content: [],
    page: 0,
    size: INVENTORY_PAGE_SIZE,
    totalElements: 0,
    totalPages: 0,
  })
  const [page, setPage] = useState(0)
  const [selectableDevices, setSelectableDevices] = useState([])
  const [patients, setPatients] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [assignment, setAssignment] = useState(null)
  const [pendingReplacement, setPendingReplacement] = useState(null)
  const [replacing, setReplacing] = useState(false)

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
      const [inventoryResponse, enrolledDevicesResponse, activePatientsResponse] = await Promise.all([
        getDevices({ page, size: INVENTORY_PAGE_SIZE }),
        getDevices({ active: true, enrollmentStatus: 'ENROLLED', page: 0, size: 100 }),
        getPatients({ status: 'ACTIVE', page: 0, size: 100 }),
      ])
      setDevicesPage(inventoryResponse)
      setSelectableDevices(enrolledDevicesResponse?.content || [])
      setPatients(activePatientsResponse?.content || [])
    } catch (loadError) {
      setError(loadError.message || 'Device inventory could not be loaded.')
    } finally {
      setLoading(false)
    }
  }, [page])

  useEffect(() => {
    let active = true
    Promise.all([
      getDevices({ page, size: INVENTORY_PAGE_SIZE }),
      getDevices({ active: true, enrollmentStatus: 'ENROLLED', page: 0, size: 100 }),
      getPatients({ status: 'ACTIVE', page: 0, size: 100 }),
    ])
      .then(([inventoryResponse, enrolledDevicesResponse, activePatientsResponse]) => {
        if (active) {
          setDevicesPage(inventoryResponse)
          setSelectableDevices(enrolledDevicesResponse?.content || [])
          setPatients(activePatientsResponse?.content || [])
          setError('')
        }
      })
      .catch((loadError) => {
        if (active) setError(loadError.message || 'Device inventory could not be loaded.')
      })
      .finally(() => {
        if (active) setLoading(false)
      })
    return () => { active = false }
  }, [page])

  function handlePageChange(nextPage) {
    setLoading(true)
    setError('')
    setPage(nextPage)
  }

  useEffect(() => {
    setValue('patientId', requestedPatientId)
  }, [requestedPatientId, setValue])

  const eligibleDevices = useMemo(
    () => selectableDevices.filter((device) => device.active && device.enrollmentStatus === 'ENROLLED'),
    [selectableDevices],
  )

  async function submitAssignment(values, replaceExisting) {
    clearErrors('root.server')
    setPendingReplacement(null)
    try {
      const result = await assignDevice({
        deviceId: values.deviceId,
        patientId: values.patientId,
        replaceExisting,
      })
      setAssignment(result)
      setValue('deviceId', '')
      await loadData()
    } catch (assignError) {
      if (
        !replaceExisting
        && assignError?.status === 409
        && (assignError?.code === 'DEVICE_ALREADY_ASSIGNED'
          || assignError?.code === 'PATIENT_ALREADY_HAS_DEVICE')
      ) {
        setPendingReplacement({
          deviceId: values.deviceId,
          patientId: values.patientId,
          code: assignError.code,
          message: assignError.message,
        })
        return
      }
      setFormError('root.server', {
        type: 'server',
        message: assignError.message || 'The device could not be assigned.',
      })
    }
  }

  async function handleAssign(values) {
    await submitAssignment(values, false)
  }

  async function handleConfirmReplacement() {
    if (!pendingReplacement) return
    setReplacing(true)
    try {
      await submitAssignment(
        {
          deviceId: pendingReplacement.deviceId,
          patientId: pendingReplacement.patientId,
        },
        true,
      )
    } finally {
      setReplacing(false)
    }
  }

  const inventoryDevices = devicesPage.content || []
  const totalPages = Math.max(1, devicesPage.totalPages || 1)

  return (
    <div className="page-stack">
      <PageHeader
        eyebrow="DEVICE MANAGEMENT"
        title="Devices"
        description="Review enrollment and connectivity, then assign an active, enrolled device to a matching patient."
      />
      {assignment && (
        <div className="success-inline" role="status">
          <Check size={18} />
          <div>
            <strong>{assignment.deviceCode} assigned to {assignment.patientCode}</strong>
            <span>{mockMode ? 'Assignment is recorded in mock data only.' : 'The configured Spring Boot service confirmed this assignment.'}</span>
          </div>
          <Link className="text-link" to={`/patients/${assignment.patientId}`}>Open patient <ArrowRight size={15} /></Link>
        </div>
      )}
      {error && (
        <div className="error-state" role="alert">
          <div><strong>Device workflow could not be completed</strong><p>{error}</p></div>
          <button className="button button-outline" onClick={loadData}>Refresh inventory</button>
        </div>
      )}

      <div className="device-page-grid">
        <section className="panel assign-panel">
          <div className="panel-heading"><div><span className="section-kicker">ASSIGNMENT</span><h2>Connect a device</h2></div><span className="panel-heading-icon"><Link2 size={18} /></span></div>
          <p>Only active, enrolled devices can be assigned, and device type must match the patient’s source type.</p>
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
                {patients.map((patient) => (
                  <option key={patient.id} value={patient.id}>
                    {patient.patientCode} · {patient.alias || patient.fullName} ({patient.sourceType}
                    {patient.currentDevice ? `, has ${patient.currentDevice.deviceCode}` : ''})
                  </option>
                ))}
              </select>
              {formErrors.patientId && <small id="assign-patient-error" className="field-error" role="alert">{formErrors.patientId.message}</small>}
            </label>
            <label className="form-field">
              <span>Enrolled device <b>*</b></span>
              <select
                className="select-control form-select"
                aria-invalid={Boolean(formErrors.deviceId)}
                aria-describedby={formErrors.deviceId ? 'assign-device-error' : undefined}
                {...register('deviceId', { required: 'Select an enrolled device.' })}
                disabled={loading || eligibleDevices.length === 0}
              >
                <option value="">{eligibleDevices.length ? 'Select a device' : 'No active enrolled devices'}</option>
                {eligibleDevices.map((device) => (
                  <option key={device.id} value={device.id}>
                    {device.deviceCode} · {device.deviceType}
                    {device.currentAssignment ? ` (Assigned to ${device.currentAssignment.patientCode})` : ' (Unassigned)'}
                  </option>
                ))}
              </select>
              {!eligibleDevices.length && !loading && (
                <small className="field-help">No active, enrolled devices are available.</small>
              )}
              {formErrors.deviceId && <small id="assign-device-error" className="field-error" role="alert">{formErrors.deviceId.message}</small>}
            </label>
            <button
              type="submit"
              className="button button-primary assign-submit"
              disabled={isSubmitting || replacing || loading || !eligibleDevices.length || !selectedPatientId || !selectedDeviceId}
            >
              {isSubmitting ? <><span className="spinner" /> Assigning...</> : <>Assign device <ArrowRight size={16} /></>}
            </button>
            {formErrors.root?.server && <div className="form-error assign-form-error" role="alert">{formErrors.root.server.message}</div>}
          </form>

          {pendingReplacement && (
            <div className="stale-banner assign-form-error" role="alertdialog" aria-labelledby="reassign-confirm-title" style={{ marginTop: 14 }}>
              <AlertTriangle size={18} />
              <div style={{ flex: 1 }}>
                <strong id="reassign-confirm-title">Existing assignment conflict — confirmation required</strong>
                <span>{pendingReplacement.message}</span>
              </div>
              <div style={{ display: 'flex', gap: 8, flexShrink: 0 }}>
                <button
                  type="button"
                  className="button button-outline button-small"
                  disabled={replacing}
                  onClick={() => setPendingReplacement(null)}
                >
                  Cancel
                </button>
                <button
                  type="button"
                  className="button button-primary button-small"
                  disabled={replacing}
                  onClick={handleConfirmReplacement}
                >
                  {replacing ? 'Reassigning...' : 'Confirm reassignment'}
                </button>
              </div>
            </div>
          )}
        </section>

        <section className="panel device-note-panel">
          <div className="device-note-icon"><Radio size={19} /></div>
          <span className="section-kicker">CONNECTION STATUS</span>
          <h2>Online isn't a safety signal.</h2>
          <p>Connected means only that the system reports recent data arrival. It does not indicate a patient's medical status.</p>
          <div className="device-note-foot"><span className="environment-dot" /> {mockMode ? 'Demo inventory · not live device telemetry' : 'Connectivity status is computed from lastSeenAt by Spring Boot'}</div>
        </section>
      </div>

      <section className="panel inventory-panel">
        <div className="table-toolbar">
          <div><h2>Device inventory</h2><p>{loading ? 'Loading inventory...' : `${devicesPage.totalElements} devices registered${mockMode ? ' in mock data' : ''}`}</p></div>
          {mockMode && <span className="mock-pill">MOCK INVENTORY</span>}
        </div>
        {error && loading ? null : (
          <div className="table-scroll">
            <table className="data-table device-table">
              <thead><tr><th>Device</th><th>Type</th><th>Connectivity</th><th>Enrollment</th><th>Assigned patient</th><th>Last seen</th></tr></thead>
              <tbody>
                {inventoryDevices.map((device) => (
                  <tr key={device.id}>
                    <td><span className="device-code"><span className="device-table-icon"><Cpu size={15} /></span><span><strong>{device.deviceCode}</strong><small>{device.modelName || '—'}</small></span></span></td>
                    <td><SourceBadge sourceType={device.deviceType} /></td>
                    <td><StatusBadge status={device.connectivityStatus} /></td>
                    <td>
                      <StatusBadge status={device.enrollmentStatus} />
                      <small className="table-sub" style={{ display: 'block', marginTop: 4 }}>{device.active ? 'Active' : 'Inactive'}</small>
                    </td>
                    <td>
                      {device.currentAssignment ? (
                        <Link className="text-link" to={`/patients/${device.currentAssignment.patientId}`}>
                          {device.currentAssignment.patientCode}
                        </Link>
                      ) : (
                        <span className="table-sub">Unassigned</span>
                      )}
                    </td>
                    <td><span className="table-main">{formatLastSeen(device.lastSeenAt)}</span></td>
                  </tr>
                ))}
                {!loading && inventoryDevices.length === 0 && <tr><td className="table-empty" colSpan="6">No devices are registered.</td></tr>}
              </tbody>
            </table>
          </div>
        )}
        {loading && <div className="loading-inline"><span className="spinner" /> Loading device inventory...</div>}
        <div className="table-footer">
          <span>
            Showing {devicesPage.totalElements ? devicesPage.page * devicesPage.size + 1 : 0}–{Math.min((devicesPage.page + 1) * devicesPage.size, devicesPage.totalElements)} of {devicesPage.totalElements}
          </span>
          <div className="pagination">
            <button
              className="button button-outline button-small"
              disabled={page <= 0}
              onClick={() => handlePageChange(Math.max(0, page - 1))}
            >
              Previous
            </button>
            <span>Page {devicesPage.totalElements === 0 ? 1 : devicesPage.page + 1} of {totalPages}</span>
            <button
              className="button button-outline button-small"
              disabled={page + 1 >= totalPages || devicesPage.totalElements === 0}
              onClick={() => handlePageChange(page + 1)}
            >
              Next
            </button>
          </div>
        </div>
      </section>
      <p className="subtle-note"><Activity size={14} /> Device codes identify inventory records; they do not authenticate a sender.</p>
    </div>
  )
}
