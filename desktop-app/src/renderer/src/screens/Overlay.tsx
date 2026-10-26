import { useEffect, useState } from 'react'
import type { BlockingRule, Task } from '@shared/models'
import { RULE_TABLES, getActiveRules } from '@shared/repos/blocking'
import { TASK_TABLES, completeTask, getActiveTasks } from '@shared/repos/tasks'
import { Icon } from '../components/Icon'
import { ConfirmDialog } from '../components/ui'
import { db, useEnforcement, useQuery } from '../lib/hooks'
import { opensScreen, verificationHint } from './TaskList'

declare global {
  interface Window {
    overlayEvents: { onContext: (cb: (ctx: { exe: string; name: string }) => void) => () => void }
  }
}

/**
 * Port of BlockingActivity: shown full-screen over a blocked app with the
 * tasks that unblock it. Manual tasks complete right here; others open in
 * the main window.
 */
export function OverlayApp() {
  const [ctx, setCtx] = useState<{ exe: string; name: string } | null>(null)
  const enforcement = useEnforcement()
  const { data: tasks } = useQuery(() => getActiveTasks(db), TASK_TABLES, [], [] as Task[])
  const { data: rules } = useQuery(() => getActiveRules(db), RULE_TABLES, [], [] as BlockingRule[])
  const [pending, setPending] = useState<Task | null>(null)

  useEffect(() => {
    void window.api.window.overlayContext().then(setCtx)
    return window.overlayEvents.onContext(setCtx)
  }, [])

  const exe = ctx?.exe ?? ''
  const state = enforcement?.state
  const unmet = new Set(state?.unmetTaskIdsByApp[exe] ?? [])
  const required = tasks.filter((t) => unmet.has(t.id))
  const ruleIds = state?.rulesByApp[exe] ?? []
  const ruleNames = rules.filter((r) => ruleIds.includes(r.id)).map((r) => r.name)
  // Conditions that point at tasks this device doesn't have (yet).
  const missing = [...unmet].filter((id) => !tasks.some((t) => t.id === id)).length
  const stillBlocked = !!state && state.blockedApps.includes(exe)

  const open = (t: Task) => {
    if (opensScreen(t)) void window.api.window.openMain(`task/${t.id}`)
    else setPending(t)
  }

  return (
    <div className="overlay">
      <div className="overlay-card">
        <Icon name="block" size="xl" style={{ color: stillBlocked ? 'var(--blocked)' : 'var(--unlocked)', margin: '0 auto' }} />
        {stillBlocked ? (
          <>
            <h1 style={{ fontSize: 30, fontWeight: 500 }}>{ctx?.name ?? 'This app'} is blocked</h1>
            {ruleNames.length > 0 && <div className="muted">by {ruleNames.join(', ')}</div>}
            <div className="muted">Complete the following tasks to unblock:</div>
            <div className="stack sm">
              {required.map((t) => {
                const hint = verificationHint(t)
                return (
                  <div key={t.id} className="card row">
                    <Icon name="circle" />
                    <div className="grow">
                      <div style={{ fontWeight: 600 }}>{t.title}</div>
                      {hint && <div className="small muted">{hint}</div>}
                      {t.dueAt && <div className="small muted">Was due at {t.dueAt}</div>}
                    </div>
                    <button className="btn sm" onClick={() => open(t)}>{opensScreen(t) ? 'Open' : 'Complete'}</button>
                  </div>
                )
              })}
              {missing > 0 && <div className="card small muted">{missing} required task{missing === 1 ? '' : 's'} not on this device yet — waiting for sync.</div>}
              {required.length === 0 && missing === 0 && <div className="card small muted">This rule has no condition tasks, so it blocks while it's active.</div>}
            </div>
          </>
        ) : (
          <h1 style={{ fontSize: 28, fontWeight: 500 }}>{ctx?.name ?? 'The app'} is unblocked</h1>
        )}
        <div className="row" style={{ justifyContent: 'center', gap: 12, marginTop: 8 }}>
          <button className="btn outlined" onClick={() => void window.api.window.openMain()}>Open Brainfence</button>
          <button className="btn text" onClick={() => void window.api.window.dismissOverlay()}>Dismiss</button>
        </div>
      </div>
      {pending && (
        <ConfirmDialog
          title="Complete task?"
          message={pending.title}
          confirmLabel="Complete"
          onCancel={() => setPending(null)}
          onConfirm={() => {
            const t = pending
            setPending(null)
            void completeTask(db, t.id).then(() => window.api.blocking.reevaluate())
          }}
        />
      )}
    </div>
  )
}
