import { useState } from 'react'
import type { Task } from '@shared/models'
import { completeTask } from '@shared/repos/tasks'
import { ConfirmDialog } from '../../components/ui'
import { db, useNow } from '../../lib/hooks'
import { CompletedToday, CompletionHistory, NotYetAvailable, TaskTopBar, isBeforeStart, scheduleSummary } from './common'

/** A manual task opened directly (e.g. from the overlay or a notification). */
export function SimpleTaskScreen({ task }: { task: Task }) {
  const now = useNow(30_000)
  const [confirm, setConfirm] = useState(false)
  return (
    <>
      <TaskTopBar task={task} />
      {task.completedToday ? (
        <CompletedToday />
      ) : (
        <div className="stack lg" style={{ marginTop: 8 }}>
          {task.description && <div>{task.description}</div>}
          <div className="muted small">{scheduleSummary(task)}</div>
          <NotYetAvailable task={task} />
          <button className="btn" disabled={isBeforeStart(task, now)} onClick={() => setConfirm(true)}>Complete</button>
        </div>
      )}
      <div style={{ marginTop: 24 }}>
        <CompletionHistory taskId={task.id} />
      </div>
      {confirm && (
        <ConfirmDialog
          title="Complete task?"
          message={task.title}
          confirmLabel="Complete"
          onCancel={() => setConfirm(false)}
          onConfirm={() => {
            setConfirm(false)
            void completeTask(db, task.id)
          }}
        />
      )}
    </>
  )
}
