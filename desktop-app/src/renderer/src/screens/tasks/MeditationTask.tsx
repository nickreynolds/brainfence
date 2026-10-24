import { useState } from 'react'
import type { MeditationTimerState } from '@shared/ipc'
import { parseJsonObject } from '@shared/json'
import type { Task } from '@shared/models'
import { getTodaysVerificationData } from '@shared/repos/tasks'
import { MeditationConfig, parseMeditationConfig } from '@shared/verification'
import { Icon } from '../../components/Icon'
import { ConfirmDialog, Ring } from '../../components/ui'
import { formatClock, formatMinutes } from '../../lib/format'
import { db, useNow, useQuery, useTimers } from '../../lib/hooks'
import { useRouter } from '../../lib/router'
import { prettyExe } from '../../lib/apps'
import { NotYetAvailable, TaskTopBar, isBeforeStart } from './common'

type Action = 'startInApp' | 'startShortSit' | 'startCompanion' | 'pause' | 'resume' | 'finishEarly' | 'cancel'

/** Port of MeditationTaskScreen: in-app timer with the "short sit" middle way, or companion-app tracking. */
export function MeditationTaskScreen({ task }: { task: Task }) {
  const { back } = useRouter()
  const timers = useTimers()
  const config = parseMeditationConfig(task.verificationConfig)
  const timer = timers.meditation[task.id]
  const [confirmCancel, setConfirmCancel] = useState(false)
  const { data: todaysProof } = useQuery(() => getTodaysVerificationData(db, task.id), ['task_completions'], [task.id], null as string | null)
  const act = (a: Action) => void window.api.timers.meditation(a, task.id)

  let body: React.ReactNode
  if (!config) body = <div className="empty">This task has no meditation duration configured. Edit it to set one.</div>
  // Checked before completedToday: a session that banked a short sit is
  // already "completed" but may still be running.
  else if (timer && timer.method === 'in_app_timer') body = <InAppTimer timer={timer} act={act} onCancel={() => setConfirmCancel(true)} />
  else if (task.completedToday) body = <Completed proof={todaysProof} />
  else if (!timer) body = <NotStarted task={task} config={config} act={act} />
  else body = <CompanionTracking task={task} timer={timer} config={config} act={act} onCancel={() => setConfirmCancel(true)} />

  const shortSit = timer?.shortSitSeconds ?? 0
  return (
    <>
      <TaskTopBar task={task} />
      {body}
      {confirmCancel && (
        <ConfirmDialog
          title="Cancel meditation?"
          message={
            timer?.method === 'in_app_timer' && shortSit > (timer?.elapsedSeconds ?? 0)
              ? `Your progress will be lost. Stay until ${formatClock(shortSit)} and it counts as a short sit.`
              : 'Your progress will be lost.'
          }
          confirmLabel="Cancel Session"
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

function Completed({ proof }: { proof: string | null }) {
  const p = parseJsonObject(proof)
  const actual = Number(p.actual_seconds ?? 0)
  return (
    <div className="center-screen">
      <Icon name="check" size="xl" style={{ color: 'var(--primary)' }} />
      <div className="title-lg primary-text">Completed today</div>
      {actual > 0 && <div className="muted">{p.short_sit ? `Short sit · ${formatMinutes(actual)}` : formatMinutes(actual)}</div>}
    </div>
  )
}

function NotStarted({ task, config, act }: { task: Task; config: MeditationConfig; act: (a: Action) => void }) {
  const now = useNow(30_000)
  const locked = isBeforeStart(task, now)
  return (
    <div className="center-screen">
      <Icon name="meditation" size="xl" style={{ color: 'var(--primary)' }} />
      <div className="title-md">Meditation session</div>
      <div className="big-time">{formatClock(config.durationSeconds)}</div>
      {config.bellIntervalSeconds > 0 && <div className="small muted">Bell every {formatClock(config.bellIntervalSeconds)}</div>}
      <NotYetAvailable task={task} />
      <button className="btn" disabled={locked} onClick={() => act('startInApp')}><Icon name="play" /> Start Meditation</button>
      {config.shortSitSeconds > 0 && (
        <>
          <button className="btn outlined" disabled={locked} onClick={() => act('startShortSit')}>Short sit ({formatMinutes(config.shortSitSeconds)})</button>
          <div className="small muted">Counts for today. You'll be offered the rest afterwards.</div>
        </>
      )}
      {config.companionApps.length > 0 && (
        <>
          <button className="btn tonal" disabled={locked} onClick={() => act('startCompanion')}>Use Companion App</button>
          <div className="small muted">Detects meditation in {config.companionApps.map(prettyExe).join(', ')}</div>
        </>
      )}
    </div>
  )
}

function InAppTimer({ timer, act, onCancel }: { timer: MeditationTimerState; act: (a: Action) => void; onCancel: () => void }) {
  const elapsed = timer.elapsedSeconds
  const remaining = Math.max(0, timer.targetSeconds - elapsed)
  // A short sit counts down to its threshold first, then to the full target.
  const inShortSitPhase = timer.shortSitIntent && !timer.shortSitBanked
  const phaseTarget = inShortSitPhase ? timer.shortSitSeconds : timer.targetSeconds
  const phaseRemaining = Math.max(0, phaseTarget - elapsed)
  return (
    <div className="center-screen">
      {timer.shortSitIntent && timer.shortSitBanked && (
        <div className="card tertiary" style={{ width: '100%', maxWidth: 460, textAlign: 'left' }}>
          <div className="title-sm">Short sit complete — it counts for today.</div>
          <div className="small">Keep going for the remaining {formatClock(remaining)}, or finish here.</div>
        </div>
      )}
      <div style={{ position: 'relative', display: 'grid', placeItems: 'center' }}>
        <Ring progress={elapsed / Math.max(1, phaseTarget)} color="var(--tertiary)" />
        <div style={{ position: 'absolute' }} className="mid-time">{formatClock(phaseRemaining)}</div>
      </div>
      <div style={{ color: timer.running ? 'var(--tertiary)' : 'var(--on-surface-variant)' }}>
        {!timer.running ? 'Paused' : inShortSitPhase ? 'Short sit' : 'Meditating'}
      </div>
      <div className="small muted">{formatClock(elapsed)} elapsed of {formatClock(timer.targetSeconds)}</div>
      {timer.pauses > 0 && <div className="small muted">{timer.pauses} pause{timer.pauses === 1 ? '' : 's'}</div>}
      <div className="row" style={{ gap: 16, marginTop: 12 }}>
        {timer.shortSitBanked ? (
          // Past the threshold nothing is lost by stopping — offer Finish, not Cancel.
          <button className="btn outlined" onClick={() => act('finishEarly')}><Icon name="check" /> Finish here</button>
        ) : (
          <button className="btn outlined danger" onClick={onCancel}><Icon name="close" /> Cancel</button>
        )}
        {timer.running ? (
          <button className="btn tonal" onClick={() => act('pause')}><Icon name="pause" /> Pause</button>
        ) : (
          <button className="btn" onClick={() => act('resume')}><Icon name="play" /> Resume</button>
        )}
      </div>
    </div>
  )
}

function CompanionTracking({ task, timer, config, act, onCancel }: { task: Task; timer: MeditationTimerState; config: MeditationConfig; act: (a: Action) => void; onCancel: () => void }) {
  const now = useNow(30_000)
  const elapsed = timer.elapsedSeconds
  return (
    <div className="center-screen">
      <div style={{ position: 'relative', display: 'grid', placeItems: 'center' }}>
        <Ring progress={elapsed / Math.max(1, timer.targetSeconds)} />
        <div style={{ position: 'absolute' }} className="mid-time">{formatClock(Math.max(0, timer.targetSeconds - elapsed))}</div>
      </div>
      <div className="small muted">{formatClock(elapsed)} accumulated of {formatClock(timer.targetSeconds)}</div>
      <div style={{ color: timer.running ? 'var(--primary)' : 'var(--on-surface-variant)' }}>
        {timer.running ? `Counting in ${prettyExe(timer.companionApp ?? '')}` : `Open ${config.companionApps.map(prettyExe).join(' or ')} — time accumulates automatically`}
      </div>
      <div className="small muted" style={{ marginTop: 12 }}>Or meditate here instead</div>
      <div className="row" style={{ gap: 8 }}>
        <button className="btn tonal" disabled={isBeforeStart(task, now)} onClick={() => act('startInApp')}><Icon name="play" /> Start timer</button>
        {config.shortSitSeconds > 0 && (
          <button className="btn outlined" disabled={isBeforeStart(task, now)} onClick={() => act('startShortSit')}>Short sit ({formatMinutes(config.shortSitSeconds)})</button>
        )}
      </div>
      <button className="btn text danger" onClick={onCancel}>Cancel</button>
    </div>
  )
}
