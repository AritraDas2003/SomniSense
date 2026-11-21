import { useMemo, useState } from 'react'
import { ArrowUpRight, Search } from 'lucide-react'
import { Link } from 'react-router-dom'
import { SourceBadge, StatusBadge } from './StatusBadge'

function formatTime(value) {
  if (!value) return 'No readings yet'
  return new Intl.DateTimeFormat(undefined, { hour: 'numeric', minute: '2-digit' }).format(new Date(value))
}

export default function PatientsTable({ patients, compact = false }) {
  const [query, setQuery] = useState('')
  const [sourceFilter, setSourceFilter] = useState('ALL')
  const [statusFilter, setStatusFilter] = useState('ALL')
  const [page, setPage] = useState(1)
  const pageSize = compact ? 4 : 6

  const filteredPatients = useMemo(() => patients.filter((patient) => {
    const searchText = `${patient.patientCode} ${patient.fullName} ${patient.alias || ''}`.toLowerCase()
    const matchesQuery = searchText.includes(query.trim().toLowerCase())
    const matchesSource = sourceFilter === 'ALL' || patient.sourceType === sourceFilter
    const matchesStatus = statusFilter === 'ALL' || patient.freshness === statusFilter
    return matchesQuery && matchesSource && matchesStatus
  }), [patients, query, sourceFilter, statusFilter])

  const pageCount = Math.max(1, Math.ceil(filteredPatients.length / pageSize))
  const visiblePatients = filteredPatients.slice((page - 1) * pageSize, page * pageSize)

  function updateFilter(setter, value) {
    setter(value)
    setPage(1)
  }

  return (
    <section className="panel patient-table-panel">
      <div className="table-toolbar">
        <div className="table-toolbar-title">
          <div><h2>{compact ? 'Patients at a glance' : 'Patient directory'}</h2><p>{filteredPatients.length} records in this demo workspace</p></div>
        </div>
        <div className="table-filters">
          <label className="search-field">
            <Search size={16} />
            <input aria-label="Search patients" value={query} onChange={(event) => updateFilter(setQuery, event.target.value)} placeholder="Search patients..." />
          </label>
          <select aria-label="Filter by source" className="select-control filter-select" value={sourceFilter} onChange={(event) => updateFilter(setSourceFilter, event.target.value)}>
            <option value="ALL">All sources</option><option value="SIMULATED">Simulated</option><option value="PHYSICAL">Physical</option>
          </select>
          <select aria-label="Filter by data freshness" className="select-control filter-select" value={statusFilter} onChange={(event) => updateFilter(setStatusFilter, event.target.value)}>
            <option value="ALL">All data states</option><option value="FRESH">Fresh data</option><option value="STALE">Stale data</option><option value="MISSING">No readings</option>
          </select>
        </div>
      </div>
      <div className="table-scroll">
        <table className="data-table">
          <thead>
            <tr>
              <th>Patient</th>
              <th>Source</th>
              <th>Device / session</th>
              <th>SpO₂</th>
              <th>Heart rate</th>
              <th>Latest reading</th>
              <th>Review</th>
              <th><span className="sr-only">Open patient</span></th>
            </tr>
          </thead>
          <tbody>
            {visiblePatients.map((patient) => (
              <tr key={patient.id}>
                <td>
                  <Link className="patient-cell-link" to={`/patients/${patient.id}`}>
                    <span className="patient-avatar">{patient.fullName.split(' ').map((part) => part[0]).slice(0, 2).join('')}</span>
                    <span className="patient-cell-copy"><strong>{patient.alias || patient.fullName}</strong><small>{patient.patientCode}</small></span>
                  </Link>
                </td>
                <td><SourceBadge sourceType={patient.sourceType} /></td>
                <td>
                  <span className="table-main">{patient.device?.deviceCode || 'Not assigned'}</span>
                  <small className="table-sub">{patient.session ? 'Session active' : patient.device ? 'No active session' : '—'}</small>
                </td>
                <td><span className="metric-value">{patient.latestReading?.spo2 == null ? '—' : `${patient.latestReading.spo2}%`}</span></td>
                <td><span className="metric-value">{patient.latestReading?.heartRate == null ? '—' : `${patient.latestReading.heartRate} bpm`}</span></td>
                <td>
                  <span className="table-main">{formatTime(patient.lastReadingAt)}</span>
                  <StatusBadge status={patient.freshness} className="table-freshness" />
                </td>
                <td><StatusBadge status={patient.reviewStatus} /></td>
                <td><Link className="table-open-link" to={`/patients/${patient.id}`} aria-label={`Open ${patient.alias || patient.fullName}`}><ArrowUpRight size={17} /></Link></td>
              </tr>
            ))}
            {visiblePatients.length === 0 && (
              <tr><td className="table-empty" colSpan="8">{query || sourceFilter !== 'ALL' || statusFilter !== 'ALL' ? 'No patients match these filters.' : 'No patients have been added yet.'}</td></tr>
            )}
          </tbody>
        </table>
      </div>
      {!compact && (
        <div className="table-footer">
          <span>Showing {filteredPatients.length ? (page - 1) * pageSize + 1 : 0}–{Math.min(page * pageSize, filteredPatients.length)} of {filteredPatients.length}</span>
          <div className="pagination">
            <button className="button button-outline button-small" disabled={page <= 1} onClick={() => setPage((current) => current - 1)}>Previous</button>
            <span>Page {page} of {pageCount}</span>
            <button className="button button-outline button-small" disabled={page >= pageCount} onClick={() => setPage((current) => current + 1)}>Next</button>
          </div>
        </div>
      )}
    </section>
  )
}
