import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { StatusBadge, SourceBadge } from '../components/common/StatusBadge'

describe('StatusBadge', () => {
  it('renders Milestone 4D prediction, quality, class, and simulator badges with expected tones', () => {
    const cases = [
      ['COMPLETED', 'Completed', 'status-success'],
      ['NEEDS_REVIEW', 'Needs review', 'status-warning'],
      ['REJECTED', 'Rejected', 'status-muted'],
      ['ERROR', 'Evaluation error', 'status-danger'],
      ['VALID', 'Valid signal', 'status-success'],
      ['DEGRADED', 'Degraded signal', 'status-warning'],
      ['RUNNING', 'Streaming', 'status-success'],
      ['PAUSED', 'Paused', 'status-warning'],
      ['STOPPED', 'Stopped', 'status-muted'],
      ['FAILED', 'Worker error', 'status-danger'],
      ['NORMAL', 'Normal', 'status-success'],
      ['APNEA_EVENT', 'Apnea event', 'status-warning'],
    ]

    for (const [status, label, cls] of cases) {
      const { unmount } = render(<StatusBadge status={status} />)
      const el = screen.getByText(label)
      expect(el).toHaveClass('status-badge', cls)
      unmount()
    }
  })

  it('renders simulated and physical source badges', () => {
    const { rerender } = render(<SourceBadge sourceType="SIMULATED" />)
    expect(screen.getByText('SIMULATED')).toHaveClass('source-simulated')
    rerender(<SourceBadge sourceType="PHYSICAL" />)
    expect(screen.getByText('PHYSICAL')).toHaveClass('source-physical')
  })
})
