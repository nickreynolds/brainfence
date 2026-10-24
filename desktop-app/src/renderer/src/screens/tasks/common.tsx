import type { Task } from '@shared/models'
import { computeTaskPhase } from '@shared/recurrence'
import { getCompletionHistory } from '@shared/repos/tasks'
import { Icon } from '../../components/Icon'
import { IconButton, TopBar } from '../../components/ui'
import { capitalize, formatDateTime } from '../../lib/format'
import { db, useNow, useQuery } from '../../lib/hooks'
import { useRouter } from '../../lib/router'

/** Top bar for a task screen, with an edit action. */
export function TaskTopBar({ task }: { task: Task }) {
  const { navigate } = useRouter()
  return <TopBar title={task.title} actions={<IconButton icon="edit" title="Edit task" onClick={() => navigate(`editor/${task.id}`)} />} />
}

export function CompletedToday({ detail }: { detail?: string | null }) {
  return (
    <div className="center-screen">
      <Icon name="check" size="xl" style={{ color: 'var(--primary)' }} />
      <div className="title-lg primary-text">Completed today</div>
      {detail && <div className="muted">{detail}</div>}
    </div>
  )
}

/** "Daily · Available 07:00–10:00" style summary. */
export function scheduleSummary(task: Task): string {
  const parts: string[] = []
  parts.push(task.recurrenceType ? capitalize(task.recurrenceType) : 'One-time')
  if (task.availableFrom && task.dueAt) parts.push(`Available ${task.availableFrom}–${task.dueAt}`)
  else if (task.availableFrom) parts.push(`Available from ${task.availableFrom}`)
  else if (task.dueAt) parts.push(`Due by ${task.dueAt}`)
  return parts.join(' · ')
}

/** Before available_from the task can't be completed yet. */
export function NotYetAvailable({ task }: { task: Task }) {
  const now = useNow(30_000)
  if (computeTaskPhase(task.availableFrom, task.dueAt, now) !== 'BEFORE_START') return null
  return (
    <div className="card secondary">
      <div className="row" style={{ gap: 10 }}>
        <Icon name="lock" />
        <span>Available at {task.availableFrom}</span>
      </div>
    </div>
  )
}

export function isBeforeStart(task: Task, now = new Date()): boolean {
  return computeTaskPhase(task.availableFrom, task.dueAt, now) === 'BEFORE_START'
}

export function CompletionHistory({ taskId }: { taskId: string }) {
  const { data } = useQuery(() => getCompletionHistory(db, taskId, 14), ['task_completions'], [taskId], [])
  if (data.length === 0) return null
  return (
    <div className="stack sm">
      <div className="section-label">Recent completions</div>
      {data.map((c) => (
        <div key={c.id} className="row small muted">
          <Icon name="check" size="sm" style={{ color: 'var(--primary)' }} />
          {formatDateTime(c.completedAt)}
        </div>
      ))}
    </div>
  )
}
