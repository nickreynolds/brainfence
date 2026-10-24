import { useState } from 'react'
import { distanceMeters } from '@shared/blocking'
import type { GpsCheckResult } from '@shared/ipc'
import type { Task } from '@shared/models'
import { parseGpsConfig } from '@shared/verification'
import { Icon } from '../../components/Icon'
import { Banner } from '../../components/ui'
import { formatDateTime } from '../../lib/format'
import { useLocationStatus } from '../../lib/hooks'
import { CompletedToday, CompletionHistory, NotYetAvailable, TaskTopBar, scheduleSummary } from './common'

/**
 * Port of GpsTaskScreen. GPS tasks complete automatically from Windows
 * location fixes (every couple of minutes while one is pending) — or on the
 * phone, whichever sees it first. "Check now" forces a fresh fix.
 */
export function GpsTaskScreen({ task }: { task: Task }) {
  const config = parseGpsConfig(task.verificationConfig)
  const location = useLocationStatus()
  const [result, setResult] = useState<GpsCheckResult | null>(null)
  const [checking, setChecking] = useState(false)

  const fix = location?.lastFix
  const distance = fix && config ? distanceMeters(fix.latitude, fix.longitude, config.lat, config.lng) : null
  const check = async () => {
    setChecking(true)
    setResult(await window.api.gps.checkTask(task.id))
    setChecking(false)
  }

  return (
    <>
      <TaskTopBar task={task} />
      {task.completedToday ? (
        <CompletedToday />
      ) : !config ? (
        <div className="empty">This task has no location configured. Edit it to set one.</div>
      ) : (
        <div className="stack lg" style={{ marginTop: 8 }}>
          {task.description && <div>{task.description}</div>}
          <div className="card stack sm">
            <div className="row"><Icon name="place" style={{ color: 'var(--primary)' }} /><span className="title-sm">
              {config.mode === 'leave' ? 'Completes when you leave the area' : config.minDurationM > 0 ? `Completes after ${config.minDurationM} min at the location` : 'Completes when you arrive'}
            </span></div>
            <div className="small muted">{config.lat.toFixed(5)}, {config.lng.toFixed(5)} · {Math.round(config.radiusM)} m radius</div>
            <div className="small muted">{scheduleSummary(task)}</div>
          </div>
          <NotYetAvailable task={task} />
          {location?.access === 'denied' && (
            <Banner icon="place" onClick={() => void window.api.location.openSettings()}>
              Location access is off for desktop apps. Click to open Windows settings — or let your phone verify this task.
            </Banner>
          )}
          {fix && distance != null && (
            <div className="small muted">
              Last location {formatDateTime(fix.at)}: {Math.round(distance)} m from the target
              {fix.accuracyM != null && ` (±${Math.round(fix.accuracyM)} m)`}
            </div>
          )}
          <div className="row">
            <button className="btn tonal" onClick={() => void check()} disabled={checking}>
              <Icon name="sync" /> {checking ? 'Checking location…' : 'Check now'}
            </button>
          </div>
          {result && <div className={result.ok ? 'primary-text' : 'err-text'}>{result.message}</div>}
        </div>
      )}
      <div style={{ marginTop: 24 }}><CompletionHistory taskId={task.id} /></div>
    </>
  )
}
