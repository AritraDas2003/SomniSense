import { useMemo, useState } from 'react'
import { ArrowUpRight, Search } from 'lucide-react'
import { Link } from 'react-router-dom'
import { SourceBadge, StatusBadge } from './StatusBadge'

function formatTime(value) {
  if (!value) return 'No readings yet'
  return new Intl.DateTimeFormat(undefined, { hour: 'numeric', minute: '2-digit' }).format(new Date(value))
}

function getPatientInitials(fullName) {
  return (fullName || '?')
    .trim()
    .split(/\s+/)
    .map((part) => part[0])
    .slice(0, 2)
    .join('')
    .toUpperCase()
}

export default function PatientsTable({
  patients = [],
  compact = false,
  pageInfo = null,
  onPageChange = null,
  filters = null,
  onFiltersChange = null,
  workspaceLabel = 'in workspace',
}) {
  const [localQuery, setLocalQuery] = useState('')
  const [localSource, setLocalSource] = useState('ALL')
  const [localStatus, setLocalStatus] = useState('ALL')

  const isServerDriven = Boolean(pageInfo && onFiltersChange)
  const query = isServerDriven ? filters?.q ?? '' : localQuery
  const sourceFilter = isServerDriven ? filters?.sourceType ?? 'ALL' : localSource
  const statusFilter = isServerDriven ? filters?.status ?? 'ALL' : localStatus

  const visiblePatients = useMemo(() => {
    if (isServerDriven) return patients
    return patients.filter((patient) => {
      const searchText = `${patient.patientCode} ${patient.fullName} ${patient.alias || ''}`.toLowerCase()
      const matchesQuery = searchText.includes(query.trim().toLowerCase())
      const matchesSource = sourceFilter === 'ALL' || patient.sourceType === sourceFilter
      const matchesStatus = statusFilter === 'ALL' || patient.status === statusFilter
      return matchesQuery && matchesSource && matchesStatus
    })
  }, [isServerDriven, patients, query, sourceFilter, statusFilter])

  const totalElements = isServerDriven ? pageInfo.totalElements : visiblePatients.length
  const currentPage = isServerDriven ? pageInfo.page : 0
  const pageSize = isServerDriven ? pageInfo.size : Math.max(1, visiblePatients.length)
  const totalPages = isServerDriven ? Math.max(1, pageInfo.totalPages || 1) : 1
  const displayedRows = compact ? visiblePatients.slice(0, 4) : visiblePatients

  function handleQueryChange(nextQuery) {
    if (isServerDriven) {
      onFiltersChange({ q: nextQuery, sourceType: sourceFilter, status: statusFilter })
    } else {
      setLocalQuery(nextQuery)
    }
  }

  function handleSourceChange(nextSource) {
    if (isServerDriven) {
      onFiltersChange({ q: query, sourceType: nextSource, status: statusFilter })
    } else {
      setLocalSource(nextSource)
    }
  }

  function handleStatusChange(nextStatus) {
    if (isServerDriven) {
      onFiltersChange({ q: query, sourceType: sourceFilter, status: nextStatus })
    } else {
      setLocalStatus(nextStatus)
    }
  }

  return (
    <section className="panel patient-table-panel">
      <div className="table-toolbar">
        <div className="table-toolbar-title">
          <div>
            <h2>{compact ? 'Patients at a glance' : 'Patient directory'}</h2>
            <p>{totalElements} records {workspaceLabel}</p>
          </div>
        </div>
        <div className="table-filters">
          <label className="search-field">
            <Search size={16} />
            <input
              aria-label="Search patients"
              value={query}
              onChange={(event) => handleQueryChange(event.target.value)}
              placeholder="Search patients..."
            />
          </label>
          <select
            aria-label="Filter by source"
            className="select-control filter-select"
            value={sourceFilter}
            onChange={(event) => handleSourceChange(event.target.value)}
          >
            <option value="ALL">All sources</option>
            <option value="SIMULATED">Simulated</option>
            <option value="PHYSICAL">Physical</option>
          </select>
          <select
            aria-label="Filter by patient status"
            className="select-control filter-select"
            value={statusFilter}
            onChange={(event) => handleStatusChange(event.target.value)}
          >
            <option value="ALL">All statuses</option>
            <option value="ACTIVE">Active</option>
            <option value="INACTIVE">Inactive</option>
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
              <th>Latest reading / link</th>
              <th>Review</th>
              <th><span className="sr-only">Open patient</span></th>
            </tr>
          </thead>
          <tbody>
            {displayedRows.map((patient) => (
              <tr key={patient.id}>
                <td>
                  <Link className="patient-cell-link" to={`/patients/${patient.id}`}>
                    <span className="patient-avatar">{getPatientInitials(patient.fullName)}</span>
                    <span className="patient-cell-copy">
                      <strong>{patient.alias || patient.fullName}</strong>
                      <small>{patient.patientCode}{patient.status === 'INACTIVE' ? ' · Inactive' : ''}</small>
                    </span>
                  </Link>
                </td>
                <td><SourceBadge sourceType={patient.sourceType} /></td>
                <td>
                  <span className="table-main">{patient.currentDevice?.deviceCode || 'Not assigned'}</span>
                  <small className="table-sub">
                    {patient.activeSession
                      ? `Session active (${patient.activeSession.sessionCode})`
                      : patient.currentDevice
                        ? 'No active session'
                        : '—'}
                  </small>
                </td>
                <td>
                  <span className="metric-value">
                    {patient.latestReading?.spo2 == null ? '—' : `${patient.latestReading.spo2}%`}
                  </span>
                </td>
                <td>
                  <span className="metric-value">
                    {patient.latestReading?.heartRate == null ? '—' : `${patient.latestReading.heartRate} bpm`}
                  </span>
                </td>
                <td>
                  <span className="table-main">{formatTime(patient.latestReading?.measuredAt)}</span>
                  <StatusBadge
                    status={patient.currentDevice?.connectivityStatus || 'UNKNOWN'}
                    className="table-freshness"
                  />
                </td>
                <td>
                  {patient.reviewStatus
                    ? <StatusBadge status={patient.reviewStatus} />
                    : <span className="table-sub">Unavailable</span>}
                </td>
                <td>
                  <Link
                    className="table-open-link"
                    to={`/patients/${patient.id}`}
                    aria-label={`Open ${patient.alias || patient.fullName}`}
                  >
                    <ArrowUpRight size={17} />
                  </Link>
                </td>
              </tr>
            ))}
            {displayedRows.length === 0 && (
              <tr>
                <td className="table-empty" colSpan="8">
                  {query || sourceFilter !== 'ALL' || statusFilter !== 'ALL'
                    ? 'No patients match these filters.'
                    : 'No patients have been added yet.'}
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>

      {!compact && isServerDriven && (
        <div className="table-footer">
          <span>
            Showing {totalElements ? currentPage * pageSize + 1 : 0}–{Math.min((currentPage + 1) * pageSize, totalElements)} of {totalElements}
          </span>
          <div className="pagination">
            <button
              className="button button-outline button-small"
              disabled={currentPage <= 0}
              onClick={() => onPageChange(currentPage - 1)}
            >
              Previous
            </button>
            <span>Page {totalElements === 0 ? 1 : currentPage + 1} of {totalPages}</span>
            <button
              className="button button-outline button-small"
              disabled={currentPage + 1 >= totalPages || totalElements === 0}
              onClick={() => onPageChange(currentPage + 1)}
            >
              Next
            </button>
          </div>
        </div>
      )}
    </section>
  )
}
