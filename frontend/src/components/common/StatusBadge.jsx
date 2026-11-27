const statusLabels = {
  ACTIVE: 'Active',
  INACTIVE: 'Inactive',
  CONNECTED: 'Connected',
  DISCONNECTED: 'Disconnected',
  UNKNOWN: 'Unknown',
  FRESH: 'Fresh data',
  STALE: 'Stale',
  MISSING: 'No readings',
  ENROLLED: 'Enrolled',
  PENDING: 'Pending',
  SETUP_REQUIRED: 'Setup required',
  NEEDS_REVIEW: 'Needs review',
  NONE: 'No flags',
  COMPLETED: 'Completed',
  REJECTED: 'Rejected',
  ERROR: 'Evaluation error',
  VALID: 'Valid signal',
  DEGRADED: 'Degraded signal',
  RUNNING: 'Streaming',
  PAUSED: 'Paused',
  STOPPED: 'Stopped',
  FAILED: 'Worker error',
  NORMAL: 'Normal',
  APNEA_EVENT: 'Apnea event',
  ENDED: 'Ended',
  CANCELLED: 'Cancelled',
  SIMULATED: 'Simulated',
  PHYSICAL: 'Physical',
}

export function StatusBadge({ status, className = '' }) {
  const tone = {
    ACTIVE: 'success',
    CONNECTED: 'success',
    ENROLLED: 'success',
    FRESH: 'success',
    COMPLETED: 'success',
    VALID: 'success',
    RUNNING: 'success',
    NORMAL: 'success',
    NEEDS_REVIEW: 'warning',
    STALE: 'warning',
    PENDING: 'warning',
    SETUP_REQUIRED: 'warning',
    DEGRADED: 'warning',
    PAUSED: 'warning',
    APNEA_EVENT: 'warning',
    DISCONNECTED: 'danger',
    ERROR: 'danger',
    FAILED: 'danger',
    INACTIVE: 'muted',
    UNKNOWN: 'muted',
    MISSING: 'muted',
    NONE: 'muted',
    REJECTED: 'muted',
    STOPPED: 'muted',
    ENDED: 'muted',
    CANCELLED: 'muted',
  }[status] || 'muted'

  return <span className={`status-badge status-${tone} ${className}`}>{statusLabels[status] || status || '—'}</span>
}

export function SourceBadge({ sourceType }) {
  const simulated = sourceType === 'SIMULATED'
  return <span className={`source-badge ${simulated ? 'source-simulated' : 'source-physical'}`}>{simulated ? 'SIMULATED' : 'PHYSICAL'}</span>
}
