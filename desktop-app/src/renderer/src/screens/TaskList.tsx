import { useMemo, useState } from 'react'
import { parseInstant } from '@shared/json'
import type { BlockingRule, Task } from '@shared/models'
import type { MeditationTimerState } from '@shared/ipc'
import { computeNextOccurrence, computeTaskPhase, TimeGatePhase } from '@shared/recurrence'
import { RULE_TABLES, getActiveRules } from '@shared/repos/blocking'
import { SHOPPING_TABLES, getOpenItemCounts } from '@shared/repos/shopping'
import { TASK_TABLES, completeTask, getActiveTasks } from '@shared/repos/tasks'
import { Icon } from '../components/Icon'
import { AppIcon, Banner, ConfirmDialog, ProgressBar, TopBar } from '../components/ui'
import { capitalize, formatNextDue, plural } from '../lib/format'
import { db, useEnforcement, useLocationStatus, useNow, useQuery, useTimers } from '../lib/hooks'
import { useRouter } from '../lib/router'
import { appLabel, appPath } from '../lib/apps'

type Tab = 'active' | 'done' | 'upcoming'

export function useTasks() {
  return useQuery(() => getActiveTasks(db), TASK_TABLES, [], [] as Task[])
}

export function useActiveRules() {
  return useQuery(() => getActiveRules(db), RULE_TABLES, [], [] as BlockingRule[])
}

/** Same ordering and filtering as Android's TaskListViewModel. */
export function partitionTasks(tasks: Task[], now: Date) {
  const phaseOf = (t: Task) => computeTaskPhase(t.availableFrom, t.dueAt, now)
  const urgency = (t: Task, phase: TimeGatePhase | null) => {
    if (t.isBlockingCondition && (phase === 'PAST_DUE' || t.dueAt == null)) return 0
    if (t.isBlockingCondition) return 1
    if (t.dueAt != null) return 2
    return 3
  }
  const active = tasks
    .map((t) => ({ t, phase: phaseOf(t) }))
    .filter(({ t, phase }) => !t.completedToday && phase !== 'BEFORE_START')
    .sort((a, b) =>
      urgency(a.t, a.phase) - urgency(b.t, b.phase)
      || (a.t.dueAt ?? '￿').localeCompare(b.t.dueAt ?? '￿')
      || a.t.sortOrder - b.t.sortOrder)
    .map(({ t }) => t)
  const done = tasks.filter((t) => t.completedToday)
  const upcoming = tasks.filter((t) => {
    if (t.completedToday && t.recurrenceType != null) return true
    if (t.completedToday) return false
    return phaseOf(t) === 'BEFORE_START'
  })
  return { active, done, upcoming }
}

/** Which screen a task opens — Android's onTaskTap mapping. Null = confirm-and-complete. */
export function opensScreen(task: Task): boolean {
  if (task.taskType === 'shopping') return true
  if (['gps', 'duration', 'meditation'].includes(task.verificationType ?? '')) return true
  return ['routine', 'workout', 'journal', 'report'].includes(task.taskType)
}

export function verificationHint(task: Task): string | null {
  if (task.taskType === 'routine' || task.taskType === 'workout') return 'Requires routine completion'
  if (task.taskType === 'journal') return 'Requires journal entry'
  if (task.taskType === 'report') return 'Requires report'
  if (task.verificationType === 'gps') return 'Requires GPS verification'
  if (task.verificationType === 'meditation') return 'Requires meditation session'
  if (task.verificationType === 'duration') return 'Requires timed session'
  return null
}

export function TaskListScreen() {
  const { navigate } = useRouter()
  const now = useNow(30_000)
  const { data: tasks } = useTasks()
  const { data: rules } = useActiveRules()
  const { data: shoppingCounts } = useQuery(() => getOpenItemCounts(db), SHOPPING_TABLES, [], {} as Record<string, number>)
  const enforcement = useEnforcement()
  const timers = useTimers()
  const location = useLocationStatus()
  const [tab, setTab] = useState<Tab>('active')
  const [pending, setPending] = useState<Task | null>(null)
  const [settingUpWeb, setSettingUpWeb] = useState(false)

  const { active, done, upcoming } = useMemo(() => partitionTasks(tasks, now), [tasks, now])
  // A task only blocks when it's a condition of an active rule — the
  // is_blocking_condition flag alone is ignored by the engine.
  const enforced = useMemo(() => new Set(rules.flatMap((r) => r.conditionTaskIds)), [rules])
  const activelyBlocking = useMemo(() => new Set(enforcement?.state.unmetTaskIds ?? []), [enforcement])
  const shown = tab === 'active' ? active : tab === 'done' ? done : upcoming

  const needsWebsiteSetup = rules.some((r) => r.blockedDomains.length > 0) && enforcement != null && !enforcement.website.writable
  const usesLocation = tasks.some((t) => t.verificationType === 'gps') || (enforcement != null && enforcement.homePresence !== 'UNCONFIGURED')
  const locationDenied = usesLocation && location?.access === 'denied'

  const onTap = (task: Task) => {
    if (opensScreen(task)) navigate(`task/${task.id}`)
    else if (!task.completedToday) setPending(task)
  }

  return (
    <>
      <TopBar
        title="Tasks"
        back={false}
        actions={<button className="btn create" onClick={() => navigate('editor')}><Icon name="add" /> New task</button>}
      />
      <div className="tabs">
        {(['active', 'done', 'upcoming'] as Tab[]).map((t) => {
          const list = t === 'active' ? active : t === 'done' ? done : upcoming
          const label = t === 'active' ? 'Active' : t === 'done' ? 'Done' : 'Upcoming'
          return (
            <button key={t} className={`tab ${tab === t ? 'active' : ''}`} onClick={() => setTab(t)}>
              {list.length > 0 ? `${label} (${list.length})` : label}
            </button>
          )
        })}
      </div>

      {tab === 'active' && (
        <div className="stack" style={{ marginBottom: 8 }}>
          {needsWebsiteSetup && (
            <Banner
              icon="globe"
              onClick={settingUpWeb ? undefined : () => {
                setSettingUpWeb(true)
                void window.api.blocking.setupWebsiteBlocking().finally(() => setSettingUpWeb(false))
              }}
            >
              {settingUpWeb ? 'Waiting for the Windows permission prompt…' : 'Website blocking needs a one-time Windows permission. Click to allow.'}
              {enforcement?.website.error && <div className="small">{enforcement.website.error}</div>}
            </Banner>
          )}
          {locationDenied && (
            <Banner icon="place" onClick={() => void window.api.location.openSettings()}>
              Location access is off for desktop apps — needed for GPS tasks and home-only blocking. Click to open Windows settings.
            </Banner>
          )}
          {rules.length > 0 && enforcement && <BlockingStatusCard tasks={tasks} rules={rules} onTap={onTap} />}
        </div>
      )}

      {shown.length === 0 ? (
        <div className="empty">
          {tab === 'active' ? 'All tasks completed or upcoming' : tab === 'done' ? 'No tasks completed yet today' : 'No upcoming tasks'}
        </div>
      ) : (
        <div className="list">
          {shown.map((task) => (
            <TaskRow
              key={task.id}
              task={task}
              now={now}
              tab={tab}
              isEnforcedBlocker={enforced.has(task.id)}
              isActivelyBlocking={activelyBlocking.has(task.id)}
              meditation={timers.meditation[task.id]}
              shoppingCount={task.taskType === 'shopping' ? shoppingCounts[task.id] ?? 0 : null}
              onClick={() => (tab === 'upcoming' ? navigate(`editor/${task.id}`) : onTap(task))}
            />
          ))}
        </div>
      )}

      {pending && (
        <ConfirmDialog
          title="Complete task?"
          message={pending.title}
          confirmLabel="Complete"
          onCancel={() => setPending(null)}
          onConfirm={() => {
            const t = pending
            setPending(null)
            void completeTask(db, t.id)
          }}
        />
      )}
    </>
  )
}

function TaskRow({ task, now, tab, isEnforcedBlocker, isActivelyBlocking, meditation, shoppingCount, onClick }: {
  task: Task
  now: Date
  tab: Tab
  isEnforcedBlocker: boolean
  isActivelyBlocking: boolean
  meditation: MeditationTimerState | undefined
  shoppingCount: number | null
  onClick: () => void
}) {
  // Shopping lists are permanent: no completion, no schedule — just the open count.
  if (shoppingCount != null) {
    return (
      <div className="list-item clickable" onClick={onClick}>
        <Icon name="cart" style={{ color: shoppingCount > 0 ? 'var(--primary)' : 'var(--outline)' }} />
        <div className="grow">
          <div className="headline">{task.title}</div>
          <div className="supporting">{shoppingCount > 0 ? `${plural(shoppingCount, 'item')} to buy` : 'List is empty'}</div>
        </div>
      </div>
    )
  }

  const showAsCompleted = tab === 'done'
  const showAsUpcoming = tab === 'upcoming'
  const phase = computeTaskPhase(task.availableFrom, task.dueAt, now)
  const isCompletedRecurring = showAsUpcoming && task.completedToday && task.recurrenceType != null
  const isLocked = (showAsUpcoming && !isCompletedRecurring) || (phase === 'BEFORE_START' && !task.completedToday)
  const isOverdue = phase === 'PAST_DUE' && !task.completedToday
  const showMeditation = meditation && meditation.targetSeconds > 0 && !showAsCompleted && !isCompletedRecurring
  const canClick = showAsUpcoming || (!showAsCompleted && !isLocked && !isCompletedRecurring)

  let supporting: React.ReactNode = null
  if (isCompletedRecurring) {
    const next = computeNextOccurrence(task.recurrenceType, task.recurrenceConfig, parseInstant(task.lastCompletionAt), now)
    supporting = next ? `Next: ${formatNextDue(next, now)}` : 'Completed'
  } else if (showAsUpcoming && task.availableFrom) {
    supporting = `Available at ${task.availableFrom}`
  } else if (isOverdue && isActivelyBlocking) {
    supporting = <span className="blocked-text">Overdue — blocking until completed</span>
  } else if (isOverdue) {
    supporting = 'Overdue'
  } else if (phase === 'ACTIVE' && task.dueAt && !task.completedToday) {
    supporting = `Complete before ${task.dueAt}`
  } else if (task.recurrenceType) {
    const due = !showAsCompleted && isEnforcedBlocker ? (task.dueAt ? ` · Due by ${task.dueAt}` : ' · Due today') : ''
    supporting = capitalize(task.recurrenceType) + due
  }

  const leading = showAsCompleted || isCompletedRecurring
    ? <Icon name="check" style={{ color: 'var(--unlocked)' }} />
    : isLocked
      ? <Icon name="lock" style={{ color: 'var(--outline)' }} />
      : isOverdue
        ? <Icon name="warning" style={{ color: isActivelyBlocking ? 'var(--blocked)' : 'var(--on-surface-variant)' }} />
        : <Icon name="circle" style={{ color: 'var(--outline)' }} />

  return (
    <div
      className={`list-item ${canClick ? 'clickable' : ''} ${showAsCompleted || isCompletedRecurring ? 'dim' : isLocked ? 'locked' : ''}`}
      onClick={canClick ? onClick : undefined}
    >
      {leading}
      <div className="grow">
        <div className="headline">{task.title}</div>
        {showMeditation && <MeditationProgress state={meditation!} />}
        {supporting && <div className="supporting">{supporting}</div>}
      </div>
      {isEnforcedBlocker && <Icon name="block" style={{ color: 'var(--blocked)' }} />}
    </div>
  )
}

function MeditationProgress({ state }: { state: MeditationTimerState }) {
  const target = Math.max(1, state.targetSeconds)
  const elapsed = Math.min(state.elapsedSeconds, target)
  return (
    <div className="stack sm" style={{ margin: '2px 0 4px' }}>
      <div className="small" style={{ color: state.running ? 'var(--primary)' : 'var(--on-surface-variant)' }}>
        {Math.floor(elapsed / 60)} / {Math.floor(target / 60)} min today
        {state.running && ` · counting in ${state.companionApp ?? 'app'}`}
      </div>
      <ProgressBar value={elapsed / target} />
    </div>
  )
}

function BlockingStatusCard({ tasks, rules, onTap }: { tasks: Task[]; rules: BlockingRule[]; onTap: (t: Task) => void }) {
  const enforcement = useEnforcement()
  const state = enforcement?.state
  if (!state) return null
  const blockingRules = rules.filter((r) => state.blockingRuleIds.includes(r.id))
  const required = new Set(blockingRules.flatMap((r) => r.conditionTaskIds))
  const incomplete = tasks.filter((t) => required.has(t.id) && !t.completedToday)
  const total = state.blockedApps.length + state.blockedDomains.length

  if (total === 0) {
    return (
      <div className="card unlocked">
        <div className="row" style={{ gap: 12 }}>
          <Icon name="check" />
          <span>All clear — nothing blocked</span>
        </div>
      </div>
    )
  }

  return (
    <div className="card blocked">
      <div className="stack sm">
        <div className="row" style={{ gap: 12 }}>
          <Icon name="block" />
          <span className="title-sm">
            {[state.blockedApps.length ? plural(state.blockedApps.length, 'app') : '', state.blockedDomains.length ? plural(state.blockedDomains.length, 'website') : '']
              .filter(Boolean).join(' and ')} blocked
          </span>
        </div>
        <div className="row wrap" style={{ gap: '4px 14px' }}>
          {state.blockedApps.map((exe) => (
            <span key={exe} className="row small" style={{ gap: 4 }}>
              <AppIcon path={appPath(rules, exe)} />
              {appLabel(rules, exe)}
            </span>
          ))}
          {state.blockedDomains.map((d) => (
            <span key={d} className="row small" style={{ gap: 4 }}>
              <Icon name="globe" size="sm" />
              {d}
            </span>
          ))}
        </div>
        {incomplete.length > 0 && (
          <>
            <div className="small" style={{ fontWeight: 600, marginTop: 8 }}>Complete to unblock:</div>
            {incomplete.map((t) => {
              const hint = verificationHint(t)
              return (
                <div key={t.id} className="row" style={{ gap: 8 }}>
                  <Icon name="circle" size="sm" />
                  <div className="grow">
                    <div className="small">{t.title}</div>
                    {hint && <div className="small" style={{ opacity: 0.75 }}>{hint}</div>}
                  </div>
                  <button className="btn text sm" style={{ color: 'inherit' }} onClick={() => onTap(t)}>
                    {opensScreen(t) ? 'Open' : 'Complete'}
                  </button>
                </div>
              )
            })}
          </>
        )}
      </div>
    </div>
  )
}
