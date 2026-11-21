import { useCallback, useEffect, useMemo, useState } from 'react'
import { Activity, ArrowLeft, ArrowRight, Clock3, HeartPulse, Radio, RefreshCw, Waves } from 'lucide-react'
import { CartesianGrid, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { Link, useParams } from 'react-router-dom'
import PageHeader from '../../components/common/PageHeader'
import { SourceBadge, StatusBadge } from '../../components/common/StatusBadge'
import { getPatientOverview } from '../../services/patientService'

function formatTime(value) {
  if (!value) return '—'
  return new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))
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
          <LineChart data={data} margin={{ top: 14, right: 12, left: -20, bottom: 0 }}>
            <CartesianGrid stroke="#292e27" strokeDasharray="3 5" vertical={false} />
            <XAxis dataKey="timeLabel" tick={{ fill: '#8b9285', fontSize: 10 }} tickLine={false} axisLine={false} minTickGap={22} />
            <YAxis domain={domain} tick={{ fill: '#8b9285', fontSize: 10 }} tickLine={false} axisLine={false} width={34} />
            <Tooltip content={<ReadingTooltip unit={unit} />} />
            <Line type="monotone" dataKey={dataKey} stroke={color} strokeWidth={2} dot={false} activeDot={{ r: 4, fill: color, stroke: '#171a17', strokeWidth: 2 }} connectNulls={false} />
          </LineChart>
        </ResponsiveContainer>
      </div>
      <div className="chart-footnote"><span className="chart-legend-dot" style={{ backgroundColor: color }} /> {title} <span>·</span> Missing points remain as gaps</div>
    </section>
  )
}

export default function PatientDetailsPage() {
  const { patientId } = useParams()
  const [overview, setOverview] = useState(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')

  const loadOverview = useCallback(async () => {
    setLoading(true)
    setError('')
    try {
      setOverview(await getPatientOverview(patientId))
    } catch (loadError) {
      setError(loadError.message || 'Patient details could not be loaded.')
    } finally {
      setLoading(false)
    }
  }, [patientId])

  useEffect(() => {
    let active = true
    getPatientOverview(patientId)
      .then((data) => { if (active) setOverview(data) })
      .catch((loadError) => { if (active) setError(loadError.message || 'Patient details could not be loaded.') })
      .finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [patientId])

  const chartData = useMemo(() => (overview?.readings || []).map((reading) => ({
    ...reading,
    timeLabel: new Intl.DateTimeFormat(undefined, { hour: 'numeric', minute: '2-digit' }).format(new Date(reading.measuredAt)),
  })), [overview])

  if (loading) return <div className="page-stack"><div className="panel loading-panel"><span className="spinner" /><span>Loading patient overview...</span></div></div>
  if (error || !overview) {
    return (
      <div className="page-stack">
        <PageHeader eyebrow="PATIENT OVERVIEW" title="Patient unavailable" description={error || 'No patient details were returned.'} />
        <div className="error-state"><div><strong>Unable to show this record.</strong><p>It may have been removed or the service is temporarily unavailable.</p></div><button className="button button-outline" onClick={loadOverview}><RefreshCw size={15} /> Try again</button></div>
        <Link className="text-link" to="/patients"><ArrowLeft size={15} /> Back to patients</Link>
      </div>
    )
  }

  const { patient, device, session, readings, predictions, freshness, mlAvailable } = overview
  const latest = [...readings].reverse().find((reading) => reading.spo2 !== null || reading.heartRate !== null)
  const latestPrediction = predictions[0]

  return (
    <div className="page-stack">
      <PageHeader
        eyebrow="PATIENT OVERVIEW"
        title={patient.alias || patient.fullName}
        description={`${patient.patientCode} · ${patient.fullName}`}
        actions={<><Link className="button button-outline" to="/patients"><ArrowLeft size={15} /> All patients</Link>{!device && <Link className="button button-primary" to={`/devices?patientId=${encodeURIComponent(patient.id)}`}>Assign device <ArrowRight size={15} /></Link>}</>}
      />
      {patient.sourceType === 'SIMULATED' && <div className="simulated-banner"><SourceBadge sourceType="SIMULATED" /><span>{overview.dataMode === 'MOCK' ? 'These readings are deterministic demo fixtures. They are not real patient measurements or live telemetry.' : 'This stream is marked simulated; it is not physical-device telemetry.'}</span></div>}
      {freshness === 'STALE' && <div className="stale-banner"><Clock3 size={17} /><div><strong>Data may be stale</strong><span>Latest measurement: {formatTime(latest?.measuredAt)}. This status is about data freshness only.</span></div></div>}
      {!mlAvailable && <div className="info-banner"><Activity size={17} /><div><strong>Model service unavailable</strong><span>Available sensor readings remain visible. No new model results are shown.</span></div></div>}

      <section className="patient-overview-grid">
        <article className="panel patient-profile-card">
          <div className="patient-profile-top"><span className="patient-avatar patient-avatar-large">{patient.fullName.split(' ').map((part) => part[0]).slice(0, 2).join('')}</span><div><span className="section-kicker">PATIENT RECORD</span><h2>{patient.alias || patient.fullName}</h2><span className="patient-code-text">{patient.patientCode}</span></div></div>
          <div className="profile-badges"><SourceBadge sourceType={patient.sourceType} /><StatusBadge status={freshness} /></div>
          <div className="profile-detail-list">
            <div><span>Device</span><strong>{device?.deviceCode || 'Not assigned'}</strong></div>
            <div><span>Session</span><strong>{session?.sessionCode || 'No active session'}</strong></div>
            <div><span>Session status</span><strong>{session?.status === 'ACTIVE' ? 'Monitoring active' : 'Not monitoring'}</strong></div>
          </div>
        </article>
        <article className="panel current-reading-card">
          <div className="panel-heading"><div><span className="section-kicker">LATEST AVAILABLE</span><h2>Reading snapshot</h2></div><Waves size={18} className="reading-heading-icon" /></div>
          {latest ? (
            <>
              <div className="reading-metrics">
                <div className="reading-metric"><span className="metric-icon metric-icon-lime"><Activity size={17} /></span><span className="metric-label">SpO₂</span><strong>{latest.spo2 == null ? '—' : latest.spo2}<small>{latest.spo2 == null ? '' : '%'}</small></strong></div>
                <div className="reading-metric"><span className="metric-icon metric-icon-coral"><HeartPulse size={17} /></span><span className="metric-label">Heart rate</span><strong>{latest.heartRate == null ? '—' : latest.heartRate}<small>{latest.heartRate == null ? '' : 'bpm'}</small></strong></div>
              </div>
              <div className="signal-quality-row"><span>Signal quality</span><strong>{latest.signalQuality == null ? '—' : `${latest.signalQuality}%`}</strong></div>
              <div className="reading-timestamps"><div><span>Measured</span><strong>{formatTime(latest.measuredAt)}</strong></div><div><span>Received</span><strong>{formatTime(latest.receivedAt)}</strong></div></div>
            </>
          ) : (
            <div className="empty-reading"><Radio size={20} /><strong>No readings available</strong><span>Values are not inferred or filled in when data is missing.</span></div>
          )}
        </article>
      </section>

      {latestPrediction && (
        <div className="review-flag"><span className="review-flag-icon"><Activity size={17} /></span><div><strong>Possible event — review</strong><span>Model output only. This is not a diagnosis. Model version: {latestPrediction.modelVersion}.</span></div><StatusBadge status={latestPrediction.predictionStatus} /></div>
      )}

      {chartData.length ? (
        <>
          <div className="charts-section-heading"><div><span className="section-kicker">TREND VIEW</span><h2>Sensor readings</h2></div><div className="chart-source-label"><SourceBadge sourceType={patient.sourceType} /><span>Measured timestamps · local time</span></div></div>
          <div className="chart-grid">
            <ReadingChart title="SpO₂" unit="%" data={chartData} dataKey="spo2" color="#b4ef55" icon={Activity} domain={[90, 100]} />
            <ReadingChart title="Heart rate" unit="bpm" data={chartData} dataKey="heartRate" color="#f58b78" icon={HeartPulse} domain={['dataMin - 8', 'dataMax + 8']} />
          </div>
        </>
      ) : (
        <section className="panel no-chart-panel"><div className="empty-chart-icon"><Activity size={21} /></div><div><h2>Trend data isn't available yet</h2><p>Charts will appear when the service provides measurements for this patient. Missing values are never filled with zero.</p></div></section>
      )}
      <div className="subtle-note"><Clock3 size={14} /> Data freshness reflects the latest received measurement; connection status is not a medical assessment.</div>
    </div>
  )
}
