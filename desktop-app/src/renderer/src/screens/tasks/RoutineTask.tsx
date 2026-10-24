import { useEffect, useState } from 'react'
import { parseJsonObject } from '@shared/json'
import type { Report, ReportSubmission, RoutineStep, Task } from '@shared/models'
import { submitReport } from '@shared/repos/reports'
import { deleteStep, insertSingleStep } from '@shared/repos/routines'
import { Icon } from '../../components/Icon'
import { ReportForm, ReportSubmissionSummary } from '../../components/ReportForm'
import { Chip, Dialog, Field, IconButton, Loading, NumberInput, ProgressBar, TextInput } from '../../components/ui'
import { formatClock } from '../../lib/format'
import { db, useNow } from '../../lib/hooks'
import { useRouter } from '../../lib/router'
import { CompletedToday, NotYetAvailable, TaskTopBar, isBeforeStart } from './common'
import { AutoProgress, SetEntry, StepState, SupersetRound, useRoutineSession } from './routineSession'

const formatWeight = (w: number) => (Number.isInteger(w) ? String(w) : String(w))

/** Port of RoutineTaskScreen (routines auto-complete; workouts have a Finish button). */
export function RoutineTaskScreen({ task }: { task: Task }) {
  const { back } = useRouter()
  const now = useNow(30_000)
  const session = useRoutineSession(task)
  const isRoutine = task.taskType === 'routine'
  const [showAdd, setShowAdd] = useState(false)
  const [submitting, setSubmitting] = useState<Set<string>>(new Set())

  // Routines finish themselves once every step is done; so does an auto routine.
  useEffect(() => {
    if (task.completedToday || session.isCompleting) return
    if ((isRoutine && session.allStepsCompleted) || (session.isAutoRoutine && session.auto.phase === 'COMPLETED')) void session.finish(back)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [session.allStepsCompleted, session.auto.phase])

  if (task.completedToday) {
    return (<><TaskTopBar task={task} /><CompletedToday /></>)
  }
  if (session.stepsLoading || (!session.initialized && session.steps.length > 0)) return (<><TaskTopBar task={task} /><Loading /></>)
  if (isBeforeStart(task, now)) return (<><TaskTopBar task={task} /><div style={{ marginTop: 8 }}><NotYetAvailable task={task} /></div></>)

  const submitReportStep = async (stepId: string, answers: Record<string, string>, note: string) => {
    const report = session.reportSteps[stepId]
    if (!report || submitting.has(stepId)) return
    setSubmitting((s) => new Set(s).add(stepId))
    try {
      await submitReport(db, report, answers, note, task.id, stepId)
    } finally {
      setSubmitting((s) => {
        const n = new Set(s)
        n.delete(stepId)
        return n
      })
    }
  }

  if (session.isAutoRoutine) {
    return (
      <>
        <TaskTopBar task={task} />
        <AutoRoutine steps={session.steps} auto={session.auto} isCompleting={session.isCompleting} onStart={session.startAuto} onStop={session.stopAuto} />
      </>
    )
  }

  // Individual steps, plus superset groups rendered once at their first step.
  const items: ({ kind: 'step'; step: RoutineStep } | { kind: 'superset'; groupId: string; steps: RoutineStep[] })[] = []
  const seen = new Set<string>()
  for (const step of session.steps) {
    if (step.supersetGroup) {
      if (seen.has(step.supersetGroup)) continue
      seen.add(step.supersetGroup)
      items.push({ kind: 'superset', groupId: step.supersetGroup, steps: session.steps.filter((s) => s.supersetGroup === step.supersetGroup) })
    } else items.push({ kind: 'step', step })
  }

  return (
    <>
      <TaskTopBar task={task} />
      <div className="stack" style={{ marginTop: 4 }}>
        {items.map((item) =>
          item.kind === 'step' ? (
            session.states[item.step.id] && (
              <StepCard
                key={item.step.id}
                state={session.states[item.step.id]}
                actions={session.actions}
                onRemove={() => void deleteStep(db, item.step.id)}
                report={session.reportSteps[item.step.id]}
                submission={session.submissions[item.step.id]}
                isSubmitting={submitting.has(item.step.id)}
                onSubmitReport={(a, n) => void submitReportStep(item.step.id, a, n)}
              />
            )
          ) : (
            <SupersetCard key={item.groupId} steps={item.steps} states={session.states} round={session.rounds[item.groupId]} actions={session.actions} groupId={item.groupId} />
          ),
        )}
        <button className="btn outlined block" onClick={() => setShowAdd(true)}>
          <Icon name="add" /> {isRoutine ? 'Add Step' : 'Add Exercise'}
        </button>
        {!isRoutine && (
          <button className="btn block" disabled={session.isCompleting} onClick={() => void session.finish(back)}>
            {session.isCompleting ? 'Saving...' : 'Finish Workout'}
          </button>
        )}
      </div>
      {showAdd && (
        <AddStepDialog
          isWorkout={!isRoutine}
          onClose={() => setShowAdd(false)}
          onAdd={(title, stepType, sets, seconds) => {
            const config =
              stepType === 'weight_reps' || stepType === 'just_reps' ? { default_sets: sets }
              : stepType === 'timed' ? { duration_seconds: seconds }
              : {}
            const order = Math.max(-1, ...session.steps.map((s) => s.stepOrder)) + 1
            void insertSingleStep(db, task.id, { title, stepType, config: JSON.stringify(config), supersetGroup: null }, order)
          }}
        />
      )}
    </>
  )
}

type Actions = ReturnType<typeof useRoutineSession>['actions']

function StepCard({ state, actions, onRemove, report, submission, isSubmitting, onSubmitReport }: {
  state: StepState; actions: Actions; onRemove: () => void
  report: Report | null | undefined; submission: ReportSubmission | undefined; isSubmitting: boolean
  onSubmitReport: (answers: Record<string, string>, note: string) => void
}) {
  const { step } = state
  const done = state.sets.every((e) => e.completed)
  return (
    <div className="card" style={done ? { background: 'color-mix(in srgb, var(--primary-container) 35%, var(--surface))' } : undefined}>
      <div className="stack">
        <div className="row">
          <Icon name={done ? 'check' : 'circle'} style={{ color: done ? 'var(--primary)' : 'var(--outline)' }} />
          <div className="grow title-md">{step.title}</div>
          <IconButton icon="close" title="Remove step" small onClick={onRemove} />
        </div>
        {step.description && <StepNotes text={step.description} />}
        {step.stepType === 'checkbox' && (
          <button className="btn tonal block" onClick={() => actions.toggleCheckbox(step.id)}>
            <Icon name={state.sets[0]?.completed ? 'check' : 'circle'} /> {state.sets[0]?.completed ? 'Done' : 'Mark Complete'}
          </button>
        )}
        {(step.stepType === 'weight_reps' || step.stepType === 'just_reps') && (
          <SetsContent state={state} showWeight={step.stepType === 'weight_reps'} actions={actions} />
        )}
        {step.stepType === 'timed' && state.sets[0] && (
          <TimedContent entry={state.sets[0]} config={step.config} onStart={() => actions.startTimer(step.id, 0)} onStop={() => actions.stopTimer(step.id, 0)} />
        )}
        {step.stepType === 'timed_sets' && (
          <div className="small muted">{state.sets.filter((e) => e.completed).length} / {state.sets.length} reps done</div>
        )}
        {step.stepType === 'report' && (
          submission ? <ReportSubmissionSummary submission={submission} />
          : !report ? <div className="small muted">This step's report can't be found. Edit the routine to choose one.</div>
          : <ReportForm report={report} isSubmitting={isSubmitting} onSubmit={onSubmitReport} />
        )}
      </div>
    </div>
  )
}

function StepNotes({ text }: { text: string }) {
  const [expanded, setExpanded] = useState(false)
  return (
    <div
      className="small muted"
      style={{ paddingLeft: 28, cursor: 'pointer', whiteSpace: 'pre-wrap', ...(expanded ? {} : { display: '-webkit-box', WebkitLineClamp: 2, WebkitBoxOrient: 'vertical', overflow: 'hidden' }) }}
      onClick={() => setExpanded(!expanded)}
    >
      {text}
    </div>
  )
}

function SetsContent({ state, showWeight, actions }: { state: StepState; showWeight: boolean; actions: Actions }) {
  const id = state.step.id
  const allDone = state.sets.every((e) => e.completed)
  const active = state.sets[state.activeSetIndex]
  const summary = (e: SetEntry, i: number) => (
    <div key={i} className="row small muted" style={{ cursor: 'pointer' }} onClick={() => actions.goToSet(id, i)}>
      <Icon name="check" size="sm" style={{ color: 'var(--primary)' }} />
      Set {i + 1}: {showWeight && e.weight != null ? `${formatWeight(e.weight)} lbs × ` : ''}{e.reps ?? 0} reps
    </div>
  )
  return (
    <div className="stack sm">
      {allDone
        ? state.sets.map(summary)
        : state.sets.map((e, i) => (e.completed && i !== state.activeSetIndex ? summary(e, i) : null))}
      {!allDone && active && (
        <>
          {state.sets.length > 1 && <div className="title-sm primary-text">Set {state.activeSetIndex + 1} of {state.sets.length}</div>}
          {showWeight && (
            <Stepper label="Weight (lbs)" value={active.weight ?? 0} step={2.5} max={500}
              onChange={(w) => actions.updateSet(id, state.activeSetIndex, { ...active, weight: w })} />
          )}
          <Stepper label="Reps" value={active.reps ?? 0} step={1} max={100}
            onChange={(r) => actions.updateSet(id, state.activeSetIndex, { ...active, reps: r })} />
          {active.completed ? (
            <button className="btn text" onClick={() => actions.updateSet(id, state.activeSetIndex, { ...active, completed: false })}>Undo Complete</button>
          ) : (
            <button className="btn block" onClick={() => actions.completeCurrentSet(id)}><Icon name="check" /> Complete Set</button>
          )}
        </>
      )}
      <div className="row" style={{ justifyContent: 'center' }}>
        <button className="btn text sm" disabled={state.sets.length <= 1} onClick={() => actions.removeSet(id)}><Icon name="remove" size="sm" /> Remove Set</button>
        <button className="btn text sm" onClick={() => actions.addSet(id)}><Icon name="add" size="sm" /> Add Set</button>
      </div>
    </div>
  )
}

function Stepper({ label, value, step, max, onChange }: { label: string; value: number; step: number; max: number; onChange: (v: number) => void }) {
  const clamp = (v: number) => Math.max(0, Math.min(max, Math.round(v / step) * step))
  return (
    <div className="field">
      <label>{label}</label>
      <div className="stepper">
        <button className="btn tonal sm" disabled={value <= 0} onClick={() => onChange(clamp(value - step))} aria-label={`Decrease ${label}`}><Icon name="remove" size="sm" /></button>
        <div className="grow"><NumberInput value={value} min={0} max={max} step={step} onChange={(v) => onChange(Math.max(0, Math.min(max, v)))} /></div>
        <button className="btn tonal sm" disabled={value >= max} onClick={() => onChange(clamp(value + step))} aria-label={`Increase ${label}`}><Icon name="add" size="sm" /></button>
      </div>
    </div>
  )
}

function TimedContent({ entry, config, onStart, onStop }: { entry: SetEntry; config: string; onStart: () => void; onStop: () => void }) {
  const target = Number(parseJsonObject(config).duration_seconds ?? 60)
  const remaining = Math.max(0, target - entry.timerElapsed)
  return (
    <div className="stack sm" style={{ alignItems: 'center' }}>
      <div className="mid-time" style={{ color: entry.completed ? 'var(--primary)' : entry.timerRunning ? 'var(--tertiary)' : undefined }}>
        {formatClock(entry.completed ? 0 : remaining)}
      </div>
      <div className="small muted">
        {entry.completed ? 'Completed' : entry.timerRunning ? 'Running' : entry.timerElapsed > 0 ? 'Stopped' : `${formatClock(target)} required`}
      </div>
      {!entry.completed && (entry.timerRunning
        ? <button className="btn tonal" onClick={onStop}><Icon name="stop" /> Stop</button>
        : <button className="btn tonal" onClick={onStart}><Icon name="play" /> {entry.timerElapsed > 0 ? 'Restart' : 'Start'}</button>)}
    </div>
  )
}

function SupersetCard({ steps, states, round, actions, groupId }: { steps: RoutineStep[]; states: Record<string, StepState>; round: SupersetRound | undefined; actions: Actions; groupId: string }) {
  const current = round?.currentRound ?? 1
  const total = round?.totalRounds ?? 1
  const setIndex = current - 1
  return (
    <div className="card" style={{ background: 'color-mix(in srgb, var(--tertiary-container) 30%, var(--surface))' }}>
      <div className="stack">
        <div className="row between">
          <span className="title-md" style={{ color: 'var(--tertiary)' }}>Superset</span>
          <span className="small" style={{ color: 'var(--tertiary)', fontWeight: 600 }}>Round {current} of {total}</span>
        </div>
        {steps.map((step) => {
          const entry = states[step.id]?.sets[setIndex]
          if (!entry) return null
          const update = (e: SetEntry) => actions.updateSet(step.id, setIndex, e)
          return (
            <div key={step.id} className="card outlined stack sm">
              <div className="row">
                <Icon name={entry.completed ? 'check' : 'circle'} size="sm" style={{ color: entry.completed ? 'var(--primary)' : 'var(--outline)' }} />
                <span className="title-sm">{step.title}</span>
              </div>
              {step.stepType === 'checkbox' && <button className="btn tonal block" onClick={() => actions.toggleCheckbox(step.id)}>{entry.completed ? 'Done' : 'Mark Complete'}</button>}
              {(step.stepType === 'weight_reps' || step.stepType === 'just_reps') && (
                <>
                  {step.stepType === 'weight_reps' && <Stepper label="Weight (lbs)" value={entry.weight ?? 0} step={2.5} max={500} onChange={(w) => update({ ...entry, weight: w })} />}
                  <Stepper label="Reps" value={entry.reps ?? 0} step={1} max={100} onChange={(r) => update({ ...entry, reps: r })} />
                  <button className="btn tonal block" onClick={() => update({ ...entry, completed: !entry.completed })}>
                    <Icon name={entry.completed ? 'check' : 'circle'} /> {entry.completed ? 'Done' : 'Complete'}
                  </button>
                </>
              )}
              {step.stepType === 'timed' && (
                <TimedContent entry={entry} config={step.config} onStart={() => actions.startTimer(step.id, setIndex)} onStop={() => actions.stopTimer(step.id, setIndex)} />
              )}
            </div>
          )
        })}
        <div className="chips" style={{ justifyContent: 'center' }}>
          {Array.from({ length: total }, (_, i) => i + 1).map((r) => {
            const complete = steps.every((s) => states[s.id]?.sets[r - 1]?.completed)
            return <Chip key={r} selected={r === current} icon={complete ? 'check' : undefined} onClick={() => actions.goToRound(groupId, r)}>{r}</Chip>
          })}
        </div>
        {current < total && <button className="btn block" onClick={() => actions.advanceRound(groupId)}>Next Round</button>}
      </div>
    </div>
  )
}

function AutoRoutine({ steps, auto, isCompleting, onStart, onStop }: { steps: RoutineStep[]; auto: AutoProgress; isCompleting: boolean; onStart: () => void; onStop: () => void }) {
  const repsOf = (s: RoutineStep) => Number(parseJsonObject(s.config).reps ?? 1)
  const current = steps[auto.exerciseIndex]
  const totalWork = steps.reduce((a, s) => a + repsOf(s), 0)
  const doneWork = steps.slice(0, auto.exerciseIndex).reduce((a, s) => a + repsOf(s), 0) + auto.rep
  const color = auto.phase === 'COUNTDOWN' ? 'var(--tertiary)' : auto.phase === 'WORK' ? 'var(--primary)' : auto.phase === 'REST' ? 'var(--secondary)' : 'var(--on-surface)'

  if (auto.phase === 'IDLE') {
    return (
      <div className="stack lg" style={{ marginTop: 8 }}>
        <div className="list">
          {steps.map((s, i) => {
            const c = parseJsonObject(s.config)
            return (
              <div key={s.id} className="list-item" style={{ minHeight: 48 }}>
                <div className="grow">
                  <div className="headline">{i + 1}. {s.title}</div>
                  <div className="supporting">{Number(c.reps ?? 1)}x {Number(c.work_seconds ?? 10)}s on / {Number(c.rest_seconds ?? 20)}s off</div>
                </div>
              </div>
            )
          })}
        </div>
        <button className="btn block" onClick={onStart}><Icon name="play" /> Start Routine</button>
      </div>
    )
  }
  if (auto.phase === 'COMPLETED') return <div className="center-screen"><div className="spinner" />{isCompleting && <div className="muted">Saving…</div>}</div>
  const paused = auto.phase === 'PAUSED'
  return (
    <div className="center-screen">
      <div className="muted">Exercise {auto.exerciseIndex + 1} of {steps.length}</div>
      {(auto.phase === 'REST' || auto.phase === 'COUNTDOWN') && !paused && <div className="small" style={{ color, fontWeight: 700, letterSpacing: 1 }}>UP NEXT</div>}
      <div className="title-lg">{current?.title}</div>
      <div className="muted">Rep {auto.rep + 1} of {current ? repsOf(current) : 1}</div>
      {paused ? (
        <div className="title-md" style={{ letterSpacing: 2 }}>PAUSED</div>
      ) : (
        <>
          <div className="big-time" style={{ color, fontSize: 96 }}>{auto.secondsRemaining}</div>
          <div style={{ color, fontWeight: 700 }}>{auto.phase === 'WORK' ? 'WORK' : auto.phase === 'REST' ? 'REST' : 'GET READY'}</div>
        </>
      )}
      <div style={{ width: 'min(420px, 100%)' }}><ProgressBar value={totalWork ? doneWork / totalWork : 0} /></div>
      {paused
        ? <button className="btn" onClick={onStart}><Icon name="play" /> Resume Routine</button>
        : <button className="btn tonal" onClick={onStop}><Icon name="pause" /> Pause</button>}
    </div>
  )
}

function AddStepDialog({ isWorkout, onClose, onAdd }: { isWorkout: boolean; onClose: () => void; onAdd: (title: string, type: string, sets: number, seconds: number) => void }) {
  const [title, setTitle] = useState('')
  const [type, setType] = useState(isWorkout ? 'weight_reps' : 'checkbox')
  const [sets, setSets] = useState(3)
  const [seconds, setSeconds] = useState(60)
  const types = isWorkout
    ? [['weight_reps', 'Weight & Reps'], ['just_reps', 'Reps Only'], ['timed', 'Timed'], ['checkbox', 'Checkbox']]
    : [['checkbox', 'Checkbox'], ['timed', 'Timed'], ['weight_reps', 'Weight & Reps'], ['just_reps', 'Reps Only']]
  const add = () => {
    if (!title.trim()) return
    onAdd(title.trim(), type, sets, seconds)
    onClose()
  }
  return (
    <Dialog
      title={isWorkout ? 'Add Exercise' : 'Add Step'}
      onClose={onClose}
      actions={<><button className="btn text" onClick={onClose}>Cancel</button><button className="btn text" disabled={!title.trim()} onClick={add}>Add</button></>}
    >
      <div className="stack">
        <Field label={isWorkout ? 'Exercise name' : 'Step name'}><TextInput value={title} onChange={setTitle} autoFocus onEnter={add} /></Field>
        <Field label="Type">
          <select className="input" value={type} onChange={(e) => setType(e.target.value)}>
            {types.map(([v, l]) => <option key={v} value={v}>{l}</option>)}
          </select>
        </Field>
        {(type === 'weight_reps' || type === 'just_reps') && <Field label="Default sets"><NumberInput value={sets} min={1} max={20} onChange={setSets} /></Field>}
        {type === 'timed' && <Field label="Duration (seconds)"><NumberInput value={seconds} min={1} onChange={setSeconds} /></Field>}
      </div>
    </Dialog>
  )
}
