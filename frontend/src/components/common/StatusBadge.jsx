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
    NEEDS_REVIEW: 'warning',
    STALE: 'warning',
    PENDING: 'warning',
    SETUP_REQUIRED: 'warning',
    DISCONNECTED: 'danger',
    INACTIVE: 'muted',
    UNKNOWN: 'muted',
    MISSING: 'muted',
    NONE: 'muted',
    ENDED: 'muted',
    CANCELLED: 'muted',
  }[status] || 'muted'

  return <span className={`status-badge status-${tone} ${className}`}>{statusLabels[status] || status || '—'}</span>
}

export function SourceBadge({ sourceType }) {
  const simulated = sourceType === 'SIMULATED'
  return <span className={`source-badge ${simulated ? 'source-simulated' : 'source-physical'}`}>{simulated ? 'SIMULATED' : 'PHYSICAL'}</span>
}
