import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  Activity,
  ArrowLeft,
  ArrowRight,
  Clock3,
  HeartPulse,
  Pause,
  Play,
  Radio,
  RefreshCw,
  Square,
  Waves,
} from 'lucide-react'
import { CartesianGrid, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { Link, useParams } from 'react-router-dom'
import PageHeader from '../../components/common/PageHeader'
import { SourceBadge, StatusBadge } from '../../components/common/StatusBadge'
import {
  cancelSession,
  endSession,
  getPatientOverview,
  pauseSimulation,
  resumeSimulation,
  startSession,
  startSimulation,
  stopSimulation,
} from '../../services/patientService'

const POLL_INTERVAL_MS = 10_000

const SCENARIOS = [
  { id: 'normal_sleep', label: 'Normal sleep breathing (normal_sleep)' },
  { id: 'severe_osa', label: 'Severe OSA (severe_osa)' },
  { id: 'cheyne_stokes_csa', label: 'Cheyne-Stokes CSA (cheyne_stokes_csa)' },
  { id: 'moderate_hypopnea', label: 'Moderate hypopnea (moderate_hypopnea)' },
]

function formatTime(value) {
  if (!value) return '—'
  return new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))
}

function formatClock(value) {
  if (!value) return '—'
  return new Intl.DateTimeFormat(undefined, { hour: 'numeric', minute: '2-digit', second: '2-digit' }).format(
    new Date(value),
  )
}

function formatConfidence(score) {
  if (score == null) return '—'
  return `${(Number(score) * 100).toFixed(1)}%`
}

function getInitials(fullName) {
  return (fullName || '?')
    .trim()
    .split(/\s+/)
    .map((part) => part[0])
    .slice(0, 2)
    .join('')
    .toUpperCase()
}

function ReadingTooltip({ active, payload, label, unit }) {
  if (!active || !payload?.length) return null
  const value = payload[0]?.value
  return (
    <div className="chart-tooltip">
      <span>{label}</span>
      <strong>{value == null ? 'No reading' : `${value} ${unit}`}</strong>
    </div>
  )
}

function ReadingChart({ title, unit, data, dataKey, color, icon: Icon, domain }) {
  return (
    <section className="panel chart-panel">
      <div className="chart-heading">
        <div className={`chart-icon ${dataKey === 'spo2' ? 'chart-icon-lime' : 'chart-icon-coral'}`}><Icon size={18} /></div>
        <div><h2>{title}</h2><p>{unit === '%' ? 'Oxygen saturation' : 'Beats per minute'}</p></div>
        <span className="chart-unit">{unit}</span>
      </div>
      <div className="chart-area">
        <ResponsiveContainer width="100%" height="100%">
          <LineChart data={data} margin={{ top: 14, right: 12, left: 0, bottom: 0 }}>
            <CartesianGrid stroke="#292e27" strokeDasharray="3 5" vertical={false} />
            <XAxis dataKey="timeLabel" tick={{ fill: '#8b9285', fontSize: 10 }} tickLine={false} axisLine={false} minTickGap={22} />
            <YAxis domain={domain} tick={{ fill: '#8b9285', fontSize: 10 }} tickLine={false} axisLine={false} width={36} />
            <Tooltip content={<ReadingTooltip unit={unit} />} />
            <Line type="monotone" dataKey={dataKey} stroke={color} strokeWidth={2} dot={false} activeDot={{ r: 4, fill: color, stroke: '#171a17', strokeWidth: 2 }} connectNulls={false} />
          </LineChart>
        </ResponsiveContainer>
      </div>
      <div className="chart-footnote"><span className="chart-legend-dot" style={{ backgroundColor: color }} /> {title} <span>·</span> Missing points remain as gaps</div>
    </section>
  )
}

function PredictionOutcomeBanner({ prediction, sessionStatus }) {
  if (!prediction) {
    return (
      <div className="info-banner" role="status">
        <Activity size={17} />
        <div>
          <strong>
            {sessionStatus === 'ACTIVE'
              ? 'Evaluating initial 60s baseline window...'
              : 'No model predictions recorded for this session.'}
          </strong>
          <span>
            Predictions appear once a full 60-second window completes evaluation. Model output only. This is not a diagnosis.
          </span>
        </div>
      </div>
    )
  }

  if (prediction.predictionStatus === 'ERROR') {
    return (
      <div className="error-state prediction-outcome-card" role="status">
        <div>
          <strong>Evaluation failed after retries</strong>
          <p>
            {prediction.errorMessage || 'Temporary model evaluation failure.'} · Model output only. This is not a diagnosis.
          </p>
        </div>
        <StatusBadge status="ERROR" />
      </div>
    )
  }

  if (prediction.predictionStatus === 'REJECTED') {
    const degraded = prediction.inputQualityStatus === 'DEGRADED'
    return (
      <div className="stale-banner prediction-outcome-card" role="status">
        <Clock3 size={17} />
        <div>
          <strong>
            {degraded
              ? 'Signal quality degraded — inference skipped'
              : 'Insufficient / invalid window — inference skipped'}
          </strong>
          <span>
            {prediction.errorMessage || 'Window did not satisfy signal-quality thresholds.'} · Model output only. This is not a diagnosis.
          </span>
        </div>
        <div className="prediction-card-badges">
          <StatusBadge status="REJECTED" />
          {prediction.inputQualityStatus && <StatusBadge status={prediction.inputQualityStatus} />}
        </div>
      </div>
    )
  }

  return (
    <div className="review-flag prediction-outcome-card">
      <span className="review-flag-icon"><Activity size={17} /></span>
      <div>
        <strong>
          {prediction.predictedClass === 'APNEA_EVENT' ? 'Possible apnea event — review' : 'Window evaluated — normal baseline'}
        </strong>
        <span>
          Confidence {formatConfidence(prediction.confidenceScore)} · Model version: {prediction.modelVersion}. Model output only. This is not a diagnosis.
          {prediction.inputQualityStatus === 'DEGRADED'
            ? ' Signal quality was DEGRADED during this window.'
            : ''}
        </span>
      </div>
      <div className="prediction-card-badges">
        <StatusBadge status={prediction.predictionStatus} />
        {prediction.predictedClass && <StatusBadge status={prediction.predictedClass} />}
        {prediction.inputQualityStatus && <StatusBadge status={prediction.inputQualityStatus} />}
      </div>
    </div>
  )
}

export default function PatientDetailsPage() {
  const { patientId } = useParams()
  const [overview, setOverview] = useState(null)
  const [loading, setLoading] = useState(true)
  const [refreshing, setRefreshing] = useState(false)
  const [error, setError] = useState('')
  const [staleWarning, setStaleWarning] = useState('')
  const [actionError, setActionError] = useState('')
  const [busyAction, setBusyAction] = useState('')
  const [confirmCancel, setConfirmCancel] = useState(false)
  const [scenarioId, setScenarioId] = useState('normal_sleep')
  const [seedInput, setSeedInput] = useState('42')
  const [sessionExpired, setSessionExpired] = useState(false)

  const fetchOverview = useCallback(async ({ silent = false } = {}) => {
    if (!silent) {
      setLoading(true)
      setError('')
    } else {
      setRefreshing(true)
    }
    try {
      const next = await getPatientOverview(patientId)
      setOverview(next)
      setError('')
      setStaleWarning('')
      if (next?.simulation?.scenarioId) {
        setScenarioId(next.simulation.scenarioId)
      }
      if (next?.simulation?.seed != null) {
        setSeedInput(String(next.simulation.seed))
      }
    } catch (loadError) {
      const msg = loadError?.message || 'Patient details could not be loaded.'
      if (silent) {
        setStaleWarning(msg)
      } else {
        setError(msg)
      }
    } finally {
      if (!silent) {
        setLoading(false)
      }
      setRefreshing(false)
    }
  }, [patientId])

  useEffect(() => {
    let active = true
    getPatientOverview(patientId)
      .then((next) => {
        if (!active) return
        setOverview(next)
        setConfirmCancel(false)
        setActionError('')
        setError('')
        setStaleWarning('')
        if (next?.simulation?.scenarioId) {
          setScenarioId(next.simulation.scenarioId)
        }
        if (next?.simulation?.seed != null) {
          setSeedInput(String(next.simulation.seed))
        }
      })
      .catch((loadError) => {
        if (!active) return
        setError(loadError?.message || 'Patient details could not be loaded.')
      })
      .finally(() => {
        if (active) setLoading(false)
      })
    return () => {
      active = false
    }
  }, [patientId])

  useEffect(() => {
    function handleSessionExpired() {
      setSessionExpired(true)
    }
    window.addEventListener('somnisense:session-expired', handleSessionExpired)
    return () => window.removeEventListener('somnisense:session-expired', handleSessionExpired)
  }, [])

  const isSessionActive = overview?.session?.status === 'ACTIVE'

  useEffect(() => {
    if (!isSessionActive || sessionExpired) return undefined

    const timer = window.setInterval(() => {
      if (document.visibilityState === 'visible') {
        fetchOverview({ silent: true })
      }
    }, POLL_INTERVAL_MS)

    function handleVisibilityChange() {
      if (document.visibilityState === 'visible') {
        fetchOverview({ silent: true })
      }
    }
    document.addEventListener('visibilitychange', handleVisibilityChange)

    return () => {
      window.clearInterval(timer)
      document.removeEventListener('visibilitychange', handleVisibilityChange)
    }
  }, [isSessionActive, sessionExpired, fetchOverview])

  async function runControlAction(label, actionFn) {
    setBusyAction(label)
    setActionError('')
    try {
      await actionFn()
      setConfirmCancel(false)
      await fetchOverview({ silent: true })
    } catch (err) {
      setActionError(err?.message || `${label} failed.`)
    } finally {
      setBusyAction('')
    }
  }

  const chartData = useMemo(() => (overview?.readings || []).map((reading) => ({
    ...reading,
    timeLabel: new Intl.DateTimeFormat(undefined, { hour: 'numeric', minute: '2-digit' }).format(new Date(reading.measuredAt)),
  })), [overview])

  if (loading && !overview) {
    return (
      <div className="page-stack">
        <div className="panel loading-panel"><span className="spinner" /><span>Loading patient overview...</span></div>
      </div>
    )
  }

  if (error || !overview) {
    return (
      <div className="page-stack">
        <PageHeader eyebrow="PATIENT OVERVIEW" title="Patient unavailable" description={error || 'No patient details were returned.'} />
        <div className="error-state">
          <div>
            <strong>Unable to show this record.</strong>
            <p>{error || 'It may not exist or the service is temporarily unavailable.'}</p>
          </div>
          <button type="button" className="button button-outline" onClick={() => fetchOverview({ silent: false })}>
            <RefreshCw size={15} /> Try again
          </button>
        </div>
        <Link className="text-link" to="/patients"><ArrowLeft size={15} /> Back to patients</Link>
      </div>
    )
  }

  const { patient, session, latestReadings, predictions = [], simulation, mlAvailable } = overview
  const currentDevice = patient.currentDevice
  const connectivityStatus = latestReadings?.connectivityStatus || currentDevice?.connectivityStatus || 'UNKNOWN'
  const latestRaw = latestReadings?.latest || null
  const lastSpo2 = latestReadings?.lastSpo2 || null
  const lastHeartRate = latestReadings?.lastHeartRate || null
  const displayedSpo2 = latestRaw?.spo2 ?? lastSpo2?.value ?? null
  const displayedHr = latestRaw?.heartRate ?? lastHeartRate?.value ?? null
  const hasAnyReading = Boolean(latestRaw || lastSpo2 || lastHeartRate)
  const latestPrediction = overview.latestPrediction ?? predictions?.[0] ?? null
  const isLiveMode = overview.dataMode === 'LIVE'
  const isSimulated = (session?.sourceType || patient.sourceType) === 'SIMULATED'
  const simRunningOrPaused = simulation?.status === 'RUNNING' || simulation?.status === 'PAUSED'

  return (
    <div className="page-stack">
      <PageHeader
        eyebrow="PATIENT OVERVIEW"
        title={patient.alias || patient.fullName}
        description={`${patient.patientCode} · ${patient.fullName}${overview.generatedAt ? ` · Updated ${formatClock(overview.generatedAt)}` : ''}`}
        actions={(
          <>
            <button
              type="button"
              className="button button-outline"
              onClick={() => fetchOverview({ silent: true })}
              disabled={refreshing}
            >
              <RefreshCw size={15} className={refreshing ? 'spin-icon' : ''} />
              {refreshing ? 'Refreshing...' : 'Refresh'}
            </button>
            <Link className="button button-outline" to="/patients"><ArrowLeft size={15} /> All patients</Link>
            {!currentDevice && (
              <Link className="button button-primary" to={`/devices?patientId=${encodeURIComponent(patient.id)}`}>
                Assign device <ArrowRight size={15} />
              </Link>
            )}
          </>
        )}
      />

      {patient.sourceType === 'SIMULATED' && (
        <div className="simulated-banner">
          <SourceBadge sourceType="SIMULATED" />
          <span>
            {overview.dataMode === 'MOCK'
              ? 'These readings are deterministic demo fixtures. They are not real patient measurements or live telemetry.'
              : 'This patient stream is marked SIMULATED; readings are not physical-device clinical telemetry.'}
          </span>
        </div>
      )}

      {(connectivityStatus === 'STALE' || connectivityStatus === 'DISCONNECTED') && (
        <div className="stale-banner">
          <Clock3 size={17} />
          <div>
            <strong>Device connectivity: {connectivityStatus}</strong>
            <span>
              Last device activity: {formatTime(latestReadings?.deviceLastSeenAt || currentDevice?.lastSeenAt)}.
              Connectivity reflects data arrival only, not clinical status.
            </span>
          </div>
        </div>
      )}

      {staleWarning && (
        <div className="stale-banner" role="status">
          <Clock3 size={17} />
          <div>
            <strong>Live poll delayed — displaying last fetched data</strong>
            <span>{staleWarning}</span>
          </div>
        </div>
      )}

      {!mlAvailable && (
        <div className="info-banner">
          <Activity size={17} />
          <div>
            <strong>Model service unavailable</strong>
            <span>Available sensor readings remain visible. Model prediction endpoints are not active yet.</span>
          </div>
        </div>
      )}

      <section className="patient-overview-grid">
        <article className="panel patient-profile-card">
          <div className="patient-profile-top">
            <span className="patient-avatar patient-avatar-large">{getInitials(patient.fullName)}</span>
            <div>
              <span className="section-kicker">PATIENT RECORD</span>
              <h2>{patient.alias || patient.fullName}</h2>
              <span className="patient-code-text">{patient.patientCode}</span>
            </div>
          </div>
          <div className="profile-badges">
            <SourceBadge sourceType={patient.sourceType} />
            <StatusBadge status={patient.status} />
            {currentDevice && <StatusBadge status={connectivityStatus} />}
          </div>
          <div className="profile-detail-list">
            <div><span>Device</span><strong>{currentDevice?.deviceCode || 'Not assigned'}</strong></div>
            <div><span>Session</span><strong>{session?.sessionCode || patient.activeSession?.sessionCode || 'No session'}</strong></div>
            <div><span>Session status</span><strong>{session?.status || (patient.activeSession ? 'ACTIVE' : 'Not monitoring')}</strong></div>
          </div>
        </article>

        <article className="panel current-reading-card">
          <div className="panel-heading">
            <div><span className="section-kicker">LATEST AVAILABLE</span><h2>Reading snapshot</h2></div>
            <Waves size={18} className="reading-heading-icon" />
          </div>
          {hasAnyReading ? (
            <>
              <div className="reading-metrics">
                <div className="reading-metric">
                  <span className="metric-icon metric-icon-lime"><Activity size={17} /></span>
                  <span className="metric-label">SpO₂</span>
                  <strong>{displayedSpo2 == null ? '—' : displayedSpo2}<small>{displayedSpo2 == null ? '' : '%'}</small></strong>
                </div>
                <div className="reading-metric">
                  <span className="metric-icon metric-icon-coral"><HeartPulse size={17} /></span>
                  <span className="metric-label">Heart rate</span>
                  <strong>{displayedHr == null ? '—' : displayedHr}<small>{displayedHr == null ? '' : 'bpm'}</small></strong>
                </div>
              </div>
              <div className="signal-quality-row">
                <span>Signal quality</span>
                <strong>{latestRaw?.signalQuality == null ? '—' : `${latestRaw.signalQuality}%`}</strong>
              </div>
              <div className="reading-timestamps">
                <div><span>Measured</span><strong>{formatTime(latestRaw?.measuredAt || lastSpo2?.measuredAt || lastHeartRate?.measuredAt)}</strong></div>
                <div><span>Received</span><strong>{formatTime(latestRaw?.receivedAt)}</strong></div>
              </div>
            </>
          ) : (
            <div className="empty-reading">
              <Radio size={20} />
              <strong>No readings yet</strong>
              <span>Values are not inferred or filled with 0% / 0 bpm when data is missing.</span>
            </div>
          )}
        </article>
      </section>

      {isLiveMode && (currentDevice || session) && (
        <section className="panel control-panel" aria-label="Monitoring session and simulator controls">
          <div className="control-panel-head">
            <div>
              <span className="section-kicker">MONITORING CONTROLS</span>
              <h2>Session &amp; stream lifecycle</h2>
            </div>
            <div className="control-action-buttons">
              {(!session || session.status !== 'ACTIVE') && currentDevice && patient.status === 'ACTIVE' && (
                <button
                  type="button"
                  className="button button-primary"
                  disabled={Boolean(busyAction)}
                  onClick={() => runControlAction('Start monitoring session', () =>
                    startSession({ patientId: patient.id, deviceAssignmentId: currentDevice.assignmentId }))}
                >
                  <Play size={15} />
                  {busyAction === 'Start monitoring session' ? 'Starting...' : 'Start monitoring session'}
                </button>
              )}

              {session?.status === 'ACTIVE' && (
                <>
                  <button
                    type="button"
                    className="button button-outline"
                    disabled={Boolean(busyAction)}
                    onClick={() => runControlAction('End session', () => endSession(session.id))}
                  >
                    <Square size={14} />
                    {busyAction === 'End session' ? 'Ending...' : 'End session'}
                  </button>
                  {!confirmCancel ? (
                    <button
                      type="button"
                      className="button button-ghost"
                      disabled={Boolean(busyAction)}
                      onClick={() => setConfirmCancel(true)}
                    >
                      Cancel session
                    </button>
                  ) : (
                    <div className="confirm-cancel-group">
                      <span>Confirm cancel session?</span>
                      <button
                        type="button"
                        className="button button-danger button-small"
                        disabled={Boolean(busyAction)}
                        onClick={() => runControlAction('Cancel session', () => cancelSession(session.id))}
                      >
                        {busyAction === 'Cancel session' ? 'Cancelling...' : 'Confirm cancel'}
                      </button>
                      <button
                        type="button"
                        className="button button-ghost button-small"
                        disabled={Boolean(busyAction)}
                        onClick={() => setConfirmCancel(false)}
                      >
                        Keep session
                      </button>
                    </div>
                  )}
                </>
              )}
            </div>
          </div>

          {session?.status === 'ACTIVE' && isSimulated && (
            <div className="simulator-control-bar">
              <div className="sim-fields">
                <label className="sim-field">
                  <span>Scenario</span>
                  <select
                    aria-label="Simulation scenario"
                    className="select-control filter-select sim-select"
                    value={scenarioId}
                    disabled={simRunningOrPaused || Boolean(busyAction)}
                    onChange={(e) => setScenarioId(e.target.value)}
                  >
                    {SCENARIOS.map((sc) => (
                      <option key={sc.id} value={sc.id}>{sc.label}</option>
                    ))}
                  </select>
                </label>

                <label className="sim-field">
                  <span>Seed</span>
                  <input
                    aria-label="Simulation seed"
                    type="number"
                    className="form-input sim-seed-input"
                    value={seedInput}
                    disabled={simRunningOrPaused || Boolean(busyAction)}
                    onChange={(e) => setSeedInput(e.target.value)}
                  />
                </label>
              </div>

              <div className="sim-status-and-actions">
                {simulation && (
                  <div className="sim-meta-pills">
                    <StatusBadge status={simulation.status} />
                    <span>{simulation.scenarioId}</span>
                    <span>step {simulation.lastStep}</span>
                    <span>gen #{simulation.workerGeneration}</span>
                  </div>
                )}

                <div className="sim-buttons">
                  {(!simulation || !simRunningOrPaused) && (
                    <button
                      type="button"
                      className="button button-primary"
                      disabled={Boolean(busyAction)}
                      onClick={() => runControlAction('Start simulator', () =>
                        startSimulation(session.id, { scenarioId, seed: seedInput }))}
                    >
                      <Play size={15} />
                      {busyAction === 'Start simulator' ? 'Starting...' : 'Start simulator'}
                    </button>
                  )}

                  {simulation?.status === 'RUNNING' && (
                    <button
                      type="button"
                      className="button button-outline"
                      disabled={Boolean(busyAction)}
                      onClick={() => runControlAction('Pause simulator', () => pauseSimulation(session.id))}
                    >
                      <Pause size={15} />
                      {busyAction === 'Pause simulator' ? 'Pausing...' : 'Pause simulator'}
                    </button>
                  )}

                  {simulation?.status === 'PAUSED' && (
                    <button
                      type="button"
                      className="button button-primary"
                      disabled={Boolean(busyAction)}
                      onClick={() => runControlAction('Resume simulator', () => resumeSimulation(session.id))}
                    >
                      <Play size={15} />
                      {busyAction === 'Resume simulator' ? 'Resuming...' : 'Resume simulator'}
                    </button>
                  )}

                  {simRunningOrPaused && (
                    <button
                      type="button"
                      className="button button-outline"
                      disabled={Boolean(busyAction)}
                      onClick={() => runControlAction('Stop simulator', () => stopSimulation(session.id))}
                    >
                      <Square size={14} />
                      {busyAction === 'Stop simulator' ? 'Stopping...' : 'Stop simulator'}
                    </button>
                  )}
                </div>
              </div>
            </div>
          )}

          {actionError && (
            <div className="form-error" role="alert">{actionError}</div>
          )}
        </section>
      )}

      {mlAvailable && (
        <section className="prediction-section" aria-label="Model C window evaluation">
          <PredictionOutcomeBanner prediction={latestPrediction} sessionStatus={session?.status} />

          {latestPrediction && (
            <div className="panel prediction-detail-grid">
              <div>
                <span>Predicted class</span>
                <strong>{latestPrediction.predictedClass || '—'}</strong>
              </div>
              <div>
                <span>Confidence</span>
                <strong>{formatConfidence(latestPrediction.confidenceScore)}</strong>
              </div>
              <div>
                <span>Window range</span>
                <strong>{formatClock(latestPrediction.windowStart)} – {formatClock(latestPrediction.windowEnd)}</strong>
              </div>
              <div>
                <span>Processed at</span>
                <strong>{formatTime(latestPrediction.processedAt)}</strong>
              </div>
              <div>
                <span>Model version</span>
                <strong>{latestPrediction.modelVersion || '—'}</strong>
              </div>
            </div>
          )}

          {predictions.length > 0 && (
            <div className="panel patient-table-panel">
              <div className="table-toolbar">
                <div className="table-toolbar-title">
                  <div>
                    <h2>Window prediction history</h2>
                    <p>Showing latest {predictions.length} persisted evaluation windows · Model output only. This is not a diagnosis.</p>
                  </div>
                </div>
              </div>
              <div className="table-scroll">
                <table className="data-table prediction-history-table">
                  <thead>
                    <tr>
                      <th>Window</th>
                      <th>Status</th>
                      <th>Classification</th>
                      <th>Confidence</th>
                      <th>Input quality</th>
                      <th>Processed</th>
                    </tr>
                  </thead>
                  <tbody>
                    {predictions.map((pred) => (
                      <tr key={pred.id}>
                        <td>{formatClock(pred.windowStart)} – {formatClock(pred.windowEnd)}</td>
                        <td><StatusBadge status={pred.predictionStatus} /></td>
                        <td>
                          {pred.predictedClass
                            ? <StatusBadge status={pred.predictedClass} />
                            : <span className="table-sub">—</span>}
                        </td>
                        <td><span className="metric-value">{formatConfidence(pred.confidenceScore)}</span></td>
                        <td>
                          {pred.inputQualityStatus
                            ? <StatusBadge status={pred.inputQualityStatus} />
                            : <span className="table-sub">—</span>}
                        </td>
                        <td>{formatTime(pred.processedAt)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          )}
        </section>
      )}

      {chartData.length ? (
        <>
          <div className="charts-section-heading">
            <div><span className="section-kicker">TREND VIEW</span><h2>Sensor readings</h2></div>
            <div className="chart-source-label"><SourceBadge sourceType={patient.sourceType} /><span>Measured timestamps · local time</span></div>
          </div>
          <div className="chart-grid">
            <ReadingChart title="SpO₂" unit="%" data={chartData} dataKey="spo2" color="#b4ef55" icon={Activity} domain={[80, 100]} />
            <ReadingChart title="Heart rate" unit="bpm" data={chartData} dataKey="heartRate" color="#f58b78" icon={HeartPulse} domain={['dataMin - 8', 'dataMax + 8']} />
          </div>
        </>
      ) : (
        <section className="panel no-chart-panel">
          <div className="empty-chart-icon"><Activity size={21} /></div>
          <div>
            <h2>Trend data isn&apos;t available yet</h2>
            <p>Charts will appear when the service records measurements for this patient’s session. Missing values remain as gaps.</p>
          </div>
        </section>
      )}
      <div className="subtle-note"><Clock3 size={14} /> Connectivity reflects recent data arrival only; it is not a medical assessment.</div>
    </div>
  )
}
