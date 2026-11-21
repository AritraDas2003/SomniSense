const statusLabels = {
  ACTIVE: 'Active',
  CONNECTED: 'Connected',
  DISCONNECTED: 'Disconnected',
  FRESH: 'Fresh data',
  STALE: 'Stale data',
  MISSING: 'No readings',
  NEEDS_REVIEW: 'Needs review',
  NONE: 'No flags',
  SIMULATED: 'Simulated',
  PHYSICAL: 'Physical',
  ENDED: 'Ended',
}

export function StatusBadge({ status, className = '' }) {
  const tone = {
    ACTIVE: 'success',
    CONNECTED: 'success',
    FRESH: 'success',
    NEEDS_REVIEW: 'warning',
    STALE: 'warning',
    DISCONNECTED: 'danger',
    MISSING: 'muted',
    NONE: 'muted',
    ENDED: 'muted',
  }[status] || 'muted'

  return <span className={`status-badge status-${tone} ${className}`}>{statusLabels[status] || status}</span>
}

export function SourceBadge({ sourceType }) {
  const simulated = sourceType === 'SIMULATED'
  return <span className={`source-badge ${simulated ? 'source-simulated' : 'source-physical'}`}>{simulated ? 'SIMULATED' : 'PHYSICAL'}</span>
}
