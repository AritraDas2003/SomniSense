import { useCallback, useEffect, useState } from 'react'
import {
  Activity,
  ArrowRight,
  ArrowUpRight,
  BedDouble,
  CircleAlert,
  Clock3,
  Plus,
  Radio,
  RefreshCw,
} from 'lucide-react'
import { Link } from 'react-router-dom'
import PageHeader from '../../components/common/PageHeader'
import PatientsTable from '../../components/common/PatientsTable'
import { SourceBadge, StatusBadge } from '../../components/common/StatusBadge'
import { getDashboard } from '../../services/dashboardService'

const POLL_INTERVAL_MS = 10_000

const cards = [
  { key: 'patientCount', label: 'Total patients', detail: 'System-wide total', icon: BedDouble, tone: 'lime' },
  { key: 'activeSessions', label: 'Active sessions', detail: 'Monitoring in progress', icon: Activity, tone: 'blue' },
  {
    key: 'itemsNeedingReview',
    label: 'Needs review',
    detail: 'Active sessions flagged by latest window',
    unavailableDetail: 'Unavailable',
    icon: CircleAlert,
    tone: 'amber',
  },
  { key: 'dataIssues', label: 'Data issues', detail: 'Active devices stale or disconnected', icon: Radio, tone: 'rose' },
]

function formatTime(value) {
  if (!value) return '—'
  return new Intl.DateTimeFormat(undefined, { hour: 'numeric', minute: '2-digit', second: '2-digit' }).format(
    new Date(value),
  )
}

function formatConfidence(score) {
  if (score == null) return '—'
  return `${(Number(score) * 100).toFixed(1)}%`
}

export default function DashboardPage() {
  const [dashboard, setDashboard] = useState(null)
  const [error, setError] = useState('')
  const [staleWarning, setStaleWarning] = useState('')
  const [loading, setLoading] = useState(true)
  const [refreshing, setRefreshing] = useState(false)
  const [sessionExpired, setSessionExpired] = useState(false)

  const fetchDashboard = useCallback(async ({ silent = false } = {}) => {
    if (!silent) {
      setLoading(true)
      setError('')
    } else {
      setRefreshing(true)
    }
    try {
      const next = await getDashboard()
      setDashboard(next)
      setError('')
      setStaleWarning('')
    } catch (loadError) {
      const msg = loadError?.message || 'Dashboard data could not be loaded.'
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
  }, [])

  useEffect(() => {
    let active = true
    getDashboard()
      .then((next) => {
        if (!active) return
        setDashboard(next)
        setError('')
        setStaleWarning('')
      })
      .catch((loadError) => {
        if (!active) return
        setError(loadError?.message || 'Dashboard data could not be loaded.')
      })
      .finally(() => {
        if (active) setLoading(false)
      })
    return () => {
      active = false
    }
  }, [])

  useEffect(() => {
    function handleSessionExpired() {
      setSessionExpired(true)
    }
    window.addEventListener('somnisense:session-expired', handleSessionExpired)
    return () => window.removeEventListener('somnisense:session-expired', handleSessionExpired)
  }, [])

  const activeSessionCount = dashboard?.summary?.activeSessions ?? 0

  useEffect(() => {
    if (sessionExpired || activeSessionCount <= 0) return undefined

    const timer = window.setInterval(() => {
      if (document.visibilityState === 'visible') {
        fetchDashboard({ silent: true })
      }
    }, POLL_INTERVAL_MS)

    function handleVisibilityChange() {
      if (document.visibilityState === 'visible') {
        fetchDashboard({ silent: true })
      }
    }
    document.addEventListener('visibilitychange', handleVisibilityChange)

    return () => {
      window.clearInterval(timer)
      document.removeEventListener('visibilitychange', handleVisibilityChange)
    }
  }, [activeSessionCount, sessionExpired, fetchDashboard])

  const statusCounts = dashboard?.summary?.activeSessionStatuses
  const activeSessionsList = dashboard?.activeSessionsList || []

  return (
    <div className="page-stack">
      <PageHeader
        eyebrow="MONITORING OVERVIEW"
        title="Good evening"
        description="A clear view of monitored patients, active sessions, and live Model C review flags."
        actions={(
          <>
            <button
              type="button"
              className="button button-outline"
              onClick={() => fetchDashboard({ silent: Boolean(dashboard) })}
              disabled={loading || refreshing}
            >
              <RefreshCw size={15} className={refreshing ? 'spin-icon' : ''} />
              {refreshing ? 'Refreshing...' : 'Refresh'}
            </button>
            <Link className="button button-primary" to="/patients/new">
              <Plus size={17} /> Add patient
            </Link>
          </>
        )}
      />

      {dashboard?.dataMode === 'MOCK' && (
        <div className="demo-banner">
          <span className="demo-banner-icon"><Activity size={17} /></span>
          <div>
            <strong>Demo workspace</strong>
            <span>All information on this screen is mock data. Simulated readings are labelled; physical-device records are not live telemetry.</span>
          </div>
          <span className="mock-pill">MOCK API</span>
        </div>
      )}

      {staleWarning && dashboard && (
        <div className="stale-banner" role="status">
          <Clock3 size={17} />
          <div>
            <strong>Live refresh delayed — showing last updated snapshot ({formatTime(dashboard.generatedAt)})</strong>
            <span>{staleWarning}</span>
          </div>
          <button
            type="button"
            className="button button-outline button-small"
            onClick={() => fetchDashboard({ silent: true })}
          >
            Retry
          </button>
        </div>
      )}

      {error && !dashboard && (
        <div className="error-state" role="alert">
          <div>
            <strong>We couldn&apos;t load the overview.</strong>
            <p>{error}</p>
          </div>
          <button type="button" className="button button-outline" onClick={() => fetchDashboard({ silent: false })}>
            Try again
          </button>
        </div>
      )}

      <section className="summary-grid" aria-label="Workspace summary">
        {cards.map(({ key, label, detail, unavailableDetail, icon: Icon, tone }) => {
          const metricValue = dashboard?.summary?.[key]
          const isUnavailable = !loading && dashboard && metricValue == null
          return (
            <article className="summary-card panel" key={key}>
              <div className={`summary-icon summary-icon-${tone}`}><Icon size={18} /></div>
              <div className="summary-card-copy">
                <span>{label}</span>
                <strong>{loading && !dashboard ? <span className="skeleton skeleton-number" /> : metricValue ?? '—'}</strong>
                <small>{isUnavailable && unavailableDetail ? unavailableDetail : detail}</small>
              </div>
              <ArrowUpRight size={14} className="summary-card-mark" aria-hidden="true" />
            </article>
          )
        })}
      </section>

      {dashboard && (
        <section className="panel active-sessions-panel" aria-label="Active monitoring sessions">
          <div className="table-toolbar">
            <div className="table-toolbar-title">
              <div>
                <h2>Active monitoring sessions</h2>
                <p>
                  {activeSessionCount === 0
                    ? 'No active sessions currently streaming.'
                    : `Live status snapshot · Last updated ${formatTime(dashboard.generatedAt)}${
                        dashboard.modelVersion ? ` · Model ${dashboard.modelVersion}` : ''
                      }`}
                </p>
              </div>
            </div>
            {statusCounts && activeSessionCount > 0 && (
              <div className="status-breakdown-strip">
                <span className="breakdown-pill breakdown-success">{statusCounts.completed} completed</span>
                <span className="breakdown-pill breakdown-warning">{statusCounts.needsReview} needs review</span>
                <span className="breakdown-pill breakdown-warning">{statusCounts.degradedWindows} degraded</span>
                <span className="breakdown-pill breakdown-muted">{statusCounts.rejected} rejected</span>
                <span className="breakdown-pill breakdown-danger">{statusCounts.error} error</span>
                {statusCounts.pendingRetryJobs > 0 && (
                  <span className="breakdown-pill breakdown-warning">{statusCounts.pendingRetryJobs} retrying</span>
                )}
              </div>
            )}
          </div>

          {dashboard.summary?.activeSessionsTruncated && (
            <div className="info-banner active-truncation-banner">
              <CircleAlert size={16} />
              <div>
                <strong>
                  Showing newest {dashboard.summary.activeSessionsReturned} of {dashboard.summary.activeSessions} active sessions
                </strong>
                <span>Filter full rosters on the Patients workspace for sessions beyond the dashboard cap.</span>
              </div>
            </div>
          )}

          {activeSessionsList.length === 0 ? (
            <div className="active-sessions-empty">
              <Activity size={19} />
              <div>
                <strong>No active monitoring sessions</strong>
                <span>Open a patient record with an assigned device to start live monitoring or simulation.</span>
              </div>
            </div>
          ) : (
            <div className="active-sessions-grid">
              {activeSessionsList.map((item) => {
                const pred = item.latestPrediction
                return (
                  <Link
                    key={item.sessionId}
                    to={`/patients/${item.patientId}`}
                    className="active-session-card"
                  >
                    <div className="active-session-head">
                      <div>
                        <strong>{item.patientDisplayName || item.patientCode}</strong>
                        <small>{item.patientCode} · {item.sessionCode}</small>
                      </div>
                      <div className="active-session-badges">
                        {item.sourceType && <SourceBadge sourceType={item.sourceType} />}
                        <StatusBadge status={item.connectivityStatus} />
                      </div>
                    </div>

                    <div className="active-session-metrics">
                      <div>
                        <span>Device</span>
                        <strong>{item.deviceCode || '—'}</strong>
                      </div>
                      <div>
                        <span>SpO₂ / HR</span>
                        <strong>
                          {item.latestReading?.spo2 == null ? '—' : `${item.latestReading.spo2}%`}
                          {' · '}
                          {item.latestReading?.heartRate == null ? '—' : `${item.latestReading.heartRate} bpm`}
                        </strong>
                      </div>
                      <div>
                        <span>Simulator</span>
                        <strong>
                          {item.simulation ? (
                            <span className="inline-sim-meta">
                              <StatusBadge status={item.simulation.status} />
                              <small>{item.simulation.scenarioId} · step {item.simulation.lastStep}</small>
                            </span>
                          ) : '—'}
                        </strong>
                      </div>
                    </div>

                    <div className="active-session-prediction">
                      {pred ? (
                        <>
                          <div className="active-prediction-status">
                            <StatusBadge status={pred.predictionStatus} />
                            {pred.predictedClass && <StatusBadge status={pred.predictedClass} />}
                            {pred.confidenceScore != null && (
                              <span className="confidence-chip">{formatConfidence(pred.confidenceScore)}</span>
                            )}
                          </div>
                          <small>Window ended {formatTime(pred.windowEnd)}</small>
                        </>
                      ) : (
                        <>
                          <span className="waiting-prediction-text">Evaluating initial 60s baseline window...</span>
                          <small>Started {formatTime(item.startedAt)}</small>
                        </>
                      )}
                    </div>
                  </Link>
                )
              })}
            </div>
          )}
        </section>
      )}

      {loading && !dashboard ? (
        <div className="panel loading-panel" aria-label="Loading patients">
          <span className="spinner" /><span>Loading monitoring overview...</span>
        </div>
      ) : dashboard && (
        <PatientsTable
          patients={dashboard.patients}
          compact
          workspaceLabel={dashboard.dataMode === 'MOCK' ? 'in this demo workspace' : 'from SomniSense API (showing latest 4)'}
        />
      )}

      <div className="dashboard-bottom-row">
        <section className="panel insight-panel">
          <div className="panel-heading">
            <div>
              <span className="section-kicker">REVIEW WORKFLOW</span>
              <h2>Items are prompts, not diagnoses</h2>
            </div>
            <span className="insight-icon"><CircleAlert size={18} /></span>
          </div>
          <p>
            Model flags are shown as <strong>Needs review</strong> when available. Model output only. This is not a diagnosis and should be interpreted with full monitoring context.
          </p>
          <Link className="text-link" to="/patients">Review patient data <ArrowRight size={15} /></Link>
        </section>
        <section className="panel quick-action-panel">
          <span className="section-kicker">QUICK ACTION</span>
          <h2>Bring a patient into view</h2>
          <p>Add a patient record, then assign an enrolled device to begin the workflow.</p>
          <Link className="button button-secondary" to="/patients/new">Register patient <ArrowRight size={16} /></Link>
        </section>
      </div>
    </div>
  )
}
