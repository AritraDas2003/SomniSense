import { useCallback, useEffect, useState } from 'react'
import { Activity, ArrowRight, ArrowUpRight, BedDouble, CircleAlert, Plus, Radio } from 'lucide-react'
import { Link } from 'react-router-dom'
import PageHeader from '../../components/common/PageHeader'
import PatientsTable from '../../components/common/PatientsTable'
import { getDashboard } from '../../services/dashboardService'

const cards = [
  { key: 'patientCount', label: 'Total patients', detail: 'In this workspace', icon: BedDouble, tone: 'lime' },
  { key: 'activeSessions', label: 'Active sessions', detail: 'Monitoring in progress', icon: Activity, tone: 'blue' },
  { key: 'itemsNeedingReview', label: 'Needs review', detail: 'Possible event · review', icon: CircleAlert, tone: 'amber' },
  { key: 'dataIssues', label: 'Data issues', detail: 'Stale or disconnected', icon: Radio, tone: 'rose' },
]

export default function DashboardPage() {
  const [dashboard, setDashboard] = useState(null)
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(true)

  const loadDashboard = useCallback(async () => {
    setLoading(true)
    setError('')
    try {
      setDashboard(await getDashboard())
    } catch (loadError) {
      setError(loadError.message || 'Dashboard data could not be loaded.')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    let active = true
    getDashboard()
      .then((data) => { if (active) setDashboard(data) })
      .catch((loadError) => { if (active) setError(loadError.message || 'Dashboard data could not be loaded.') })
      .finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [])

  return (
    <div className="page-stack">
      <PageHeader
        eyebrow="MONITORING OVERVIEW"
        title="Good evening"
        description="A clear view of monitored patients, incoming data, and items that may need review."
        actions={<Link className="button button-primary" to="/patients/new"><Plus size={17} /> Add patient</Link>}
      />

      {dashboard?.dataMode === 'MOCK' && <div className="demo-banner">
        <span className="demo-banner-icon"><Activity size={17} /></span>
        <div><strong>Demo workspace</strong><span>All information on this screen is mock data. Simulated readings are labelled; physical-device records are not live telemetry.</span></div>
        <span className="mock-pill">MOCK API</span>
      </div>}

      {error && (
        <div className="error-state" role="alert">
          <div><strong>We couldn't load the overview.</strong><p>{error}</p></div>
          <button className="button button-outline" onClick={loadDashboard}>Try again</button>
        </div>
      )}

      <section className="summary-grid" aria-label="Workspace summary">
        {cards.map(({ key, label, detail, icon: Icon, tone }) => (
          <article className="summary-card panel" key={key}>
            <div className={`summary-icon summary-icon-${tone}`}><Icon size={18} /></div>
            <div className="summary-card-copy"><span>{label}</span><strong>{loading ? <span className="skeleton skeleton-number" /> : dashboard?.summary[key] ?? '—'}</strong><small>{detail}</small></div>
            <ArrowUpRight size={14} className="summary-card-mark" aria-hidden="true" />
          </article>
        ))}
      </section>

      {loading && !dashboard ? (
        <div className="panel loading-panel" aria-label="Loading patients">
          <span className="spinner" /><span>Loading monitoring overview...</span>
        </div>
      ) : dashboard && (
        <PatientsTable patients={dashboard.patients} compact />
      )}

      <div className="dashboard-bottom-row">
        <section className="panel insight-panel">
          <div className="panel-heading"><div><span className="section-kicker">REVIEW WORKFLOW</span><h2>Items are prompts, not diagnoses</h2></div><span className="insight-icon"><CircleAlert size={18} /></span></div>
          <p>Model flags are shown as <strong>Needs review</strong>. They do not confirm a condition and should be interpreted with the full monitoring context.</p>
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
