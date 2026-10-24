import { useState } from 'react'
import type { Task } from '@shared/models'
import { parseDurationConfig } from '@shared/verification'
import { Icon } from '../../components/Icon'
import { ConfirmDialog, Ring } from '../../components/ui'
import { formatClock } from '../../lib/format'
import { useNow, useTimers } from '../../lib/hooks'
import { useRouter } from '../../lib/router'
import { CompletedToday, NotYetAvailable, TaskTopBar, isBeforeStart } from './common'

/** Port of DurationTaskScreen: the timer runs in the main process and survives closing the window. */
export function DurationTaskScreen({ task }: { task: Task }) {
  const { back } = useRouter()
  const now = useNow(30_000)
  const timers = useTimers()
  const config = parseDurationConfig(task.verificationConfig)
  const timer = timers.duration[task.id]
  const [confirmCancel, setConfirmCancel] = useState(false)
  const act = (a: 'start' | 'pause' | 'resume' | 'cancel') => void window.api.timers.duration(a, task.id)

  return (
    <>
      <TaskTopBar task={task} />
      {task.completedToday ? (
        <CompletedToday />
      ) : !config ? (
        <div className="empty">This task has no duration configured. Edit it to set one.</div>
      ) : !timer ? (
        <div className="center-screen">
          <Icon name="timer" size="xl" style={{ color: 'var(--primary)' }} />
          <div className="title-md">Duration required</div>
          <div className="big-time">{formatClock(config.targetSeconds)}</div>
          <NotYetAvailable task={task} />
          <button className="btn" disabled={isBeforeStart(task, now)} onClick={() => act('start')}>
            <Icon name="play" /> Start Timer
          </button>
          <div className="small muted">The timer keeps running if you close this window.</div>
        </div>
      ) : (
        <div className="center-screen">
          <div style={{ position: 'relative', display: 'grid', placeItems: 'center' }}>
            <Ring progress={timer.elapsedSeconds / Math.max(1, timer.targetSeconds)} />
            <div style={{ position: 'absolute' }} className="mid-time">{formatClock(timer.targetSeconds - timer.elapsedSeconds)}</div>
          </div>
          <div className="muted">{timer.running ? 'Running' : 'Paused'}</div>
          <div className="small muted">{formatClock(timer.elapsedSeconds)} elapsed of {formatClock(timer.targetSeconds)}</div>
          <div className="row" style={{ gap: 16, marginTop: 12 }}>
            <button className="btn outlined danger" onClick={() => setConfirmCancel(true)}>
              <Icon name="close" /> Cancel
            </button>
            {timer.running ? (
              <button className="btn tonal" onClick={() => act('pause')}><Icon name="pause" /> Pause</button>
            ) : (
              <button className="btn" onClick={() => act('resume')}><Icon name="play" /> Resume</button>
            )}
          </div>
        </div>
      )}
      {confirmCancel && (
        <ConfirmDialog
          title="Cancel timer?"
          message="Your progress will be lost."
          confirmLabel="Cancel Timer"
          cancelLabel="Keep Going"
          danger
          onCancel={() => setConfirmCancel(false)}
          onConfirm={() => {
            setConfirmCancel(false)
            act('cancel')
            back()
          }}
        />
      )}
    </>
  )
}
