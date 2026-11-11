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
import { CompletedToday, NotYetAvailable, TaskTopBar, isBeforeStart, isTaskDone } from './common'
import { AutoProgress, SetEntry, StepState, SupersetRound, useRoutineSession } from './routineSession'
import { TimedSetsConfig, WeightKind, formatTimedWeight, parseTimedSetsConfig, timedSetsHangCount, timedSetsSetOf, timedSetsSideOf } from '@shared/timedSets'
import { describeGpsAlternative, parseGpsAlternatives } from '@shared/verification'

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
    if (isTaskDone(task) || session.isCompleting) return
    if ((isRoutine && session.allStepsCompleted) || (session.isAutoRoutine && session.auto.phase === 'COMPLETED')) void session.finish(back)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [session.allStepsCompleted, session.auto.phase])

  if (isTaskDone(task)) {
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
        <AlternativesNote task={task} />
        <AutoRoutine
          steps={session.steps} states={session.states} auto={session.auto} isCompleting={session.isCompleting}
          onStart={session.startAuto} onStop={session.stopAuto} onSetWeight={session.actions.setTimedSetWeight}
          onConfirmSetWeight={session.confirmSetWeight}
        />
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
        <AlternativesNote task={task} />
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
        {step.imageUrl && <StepImage url={step.imageUrl} />}
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
          <div className="small muted">{state.sets.filter((e) => e.completed).length} / {state.sets.length} hangs done</div>
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

/** "Or be at Climbing gym for 30 min": the task's other ways to complete (shared/CONTRACTS.md). */
export function AlternativesNote({ task }: { task: Task }) {
  const [checking, setChecking] = useState(false)
  const [message, setMessage] = useState<string | null>(null)
  const alternatives = parseGpsAlternatives(task.completionAlternatives)
  if (alternatives.length === 0) return null
  const check = async () => {
    setChecking(true)
    const result = await window.api.gps.checkTask(task.id)
    setMessage(result.message)
    setChecking(false)
  }
  return (
    <div className="card secondary">
      <div className="row" style={{ gap: 12 }}>
        <Icon name="place" />
        <div className="grow stack sm">
          <div className="small">
            Or {alternatives.map(describeGpsAlternative).join(', or ')}: this completes automatically from your location.
          </div>
          {message && <div className="small muted">{message}</div>}
        </div>
        <button className="btn text" disabled={checking} onClick={() => void check()}>{checking ? 'Checking…' : 'Check now'}</button>
      </div>
    </div>
  )
}

/** A step's picture (e.g. which holds to use), scaled to fit. */
function StepImage({ url, maxHeight = 220 }: { url: string; maxHeight?: number }) {
  const [failed, setFailed] = useState(false)
  if (failed || !/^https:\/\//.test(url)) return null
  return (
    <img
      src={url}
      alt=""
      onError={() => setFailed(true)}
      style={{ display: 'block', width: '100%', maxHeight, objectFit: 'contain', borderRadius: 12, background: 'var(--surface-container)' }}
    />
  )
}

function describeTimedSets(c: TimedSetsConfig): string {
  const hangs = `${c.reps}× ${c.workSeconds}s on / ${c.restSeconds}s off${c.sides === 2 ? ' per hand (left, then right)' : ''}`
  if (c.sets <= 1) return hangs
  return `${c.sets} sets of ${hangs} · ${formatClock(c.setRestSeconds)} between sets`
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

function AutoRoutine({ steps, states, auto, isCompleting, onStart, onStop, onSetWeight, onConfirmSetWeight }: {
  steps: RoutineStep[]; states: Record<string, StepState>; auto: AutoProgress; isCompleting: boolean
  onStart: () => void; onStop: () => void; onSetWeight: (stepId: string, setIndex: number, weight: number | null) => void
  onConfirmSetWeight: () => void
}) {
  const [confirming, setConfirming] = useState(false)
  const hangsOf = (s: RoutineStep) => timedSetsHangCount(parseTimedSetsConfig(s.config))
  const current = steps[auto.exerciseIndex]
  const currentConfig = current ? parseTimedSetsConfig(current.config) : null
  const totalWork = steps.reduce((a, s) => a + hangsOf(s), 0)
  const doneWork = steps.slice(0, auto.exerciseIndex).reduce((a, s) => a + hangsOf(s), 0) + auto.rep
  const color = auto.phase === 'COUNTDOWN' ? 'var(--tertiary)' : auto.phase === 'WORK' ? 'var(--primary)' : auto.phase === 'REST' ? 'var(--secondary)' : 'var(--on-surface)'
  const weightOf = (step: RoutineStep, setIndex: number) => {
    const cfg = parseTimedSetsConfig(step.config)
    return states[step.id]?.sets[setIndex * cfg.reps * cfg.sides]?.weight ?? null
  }
  // Steps whose weight is confirmed once, before the routine starts.
  const startWeighted = steps.filter((s) => {
    const c = parseTimedSetsConfig(s.config)
    return c.weight != null && c.weightPrompt === 'start'
  })

  if (auto.phase === 'IDLE' && confirming) {
    return (
      <div className="stack lg" style={{ marginTop: 8 }}>
        <div className="title-md">Confirm weights</div>
        <div className="small muted">Added weight for each hang (negative = assisted). Pre-filled from last time.</div>
        <div className="list">
          {startWeighted.map((s) => {
            const c = parseTimedSetsConfig(s.config)
            return (
              <div key={s.id} className="list-item" style={{ alignItems: 'center' }}>
                <div className="grow headline">{s.title}</div>
                <div style={{ width: 220 }}><WeightField kind={c.weight!} value={weightOf(s, 0)} onChange={(w) => onSetWeight(s.id, 0, w)} /></div>
              </div>
            )
          })}
        </div>
        <div className="row">
          <button className="btn text" onClick={() => setConfirming(false)}>Back</button>
          <button className="btn grow" onClick={() => { setConfirming(false); onStart() }}><Icon name="play" /> Confirm and start</button>
        </div>
      </div>
    )
  }

  if (auto.phase === 'IDLE') {
    return (
      <div className="stack lg" style={{ marginTop: 8 }}>
        <div className="list">
          {steps.map((s, i) => {
            const c = parseTimedSetsConfig(s.config)
            return (
              <div key={s.id} className="list-item" style={{ minHeight: 48, alignItems: 'flex-start' }}>
                <div className="grow stack sm">
                  <div className="headline">{i + 1}. {s.title}</div>
                  <div className="supporting">{describeTimedSets(c)}</div>
                  {s.description && <div className="supporting" style={{ whiteSpace: 'pre-wrap' }}>{s.description}</div>}
                  {s.imageUrl && <StepImage url={s.imageUrl} maxHeight={140} />}
                  {c.weight && <div className="supporting">{c.weightPrompt === 'each_set' ? 'Weight asked before each set' : `Last time: ${formatTimedWeight(c.weight, weightOf(s, 0))}`}</div>}
                </div>
              </div>
            )
          })}
        </div>
        <button className="btn block" onClick={() => (startWeighted.length > 0 ? setConfirming(true) : onStart())}><Icon name="play" /> Start Routine</button>
      </div>
    )
  }
  if (auto.phase === 'COMPLETED') return <div className="center-screen"><div className="spinner" />{isCompleting && <div className="muted">Saving…</div>}</div>
  const paused = auto.phase === 'PAUSED'
  const setIndex = currentConfig ? timedSetsSetOf(currentConfig, auto.rep) : 0
  const hangInSide = currentConfig ? (auto.rep % currentConfig.reps) + 1 : auto.rep + 1
  const side = currentConfig && currentConfig.sides === 2 ? (timedSetsSideOf(currentConfig, auto.rep) === 0 ? 'Left hand' : 'Right hand') : null

  if (auto.phase === 'WEIGHT' && current && currentConfig?.weight) {
    return (
      <div className="center-screen">
        <div className="muted">Exercise {auto.exerciseIndex + 1} of {steps.length}</div>
        <div className="title-lg">{current.title}</div>
        <div className="muted">Set {setIndex + 1} of {currentConfig.sets}{currentConfig.sides === 2 ? ' · left, then right, same weight' : ''}</div>
        {current.imageUrl && <div style={{ width: 'min(420px, 100%)' }}><StepImage url={current.imageUrl} /></div>}
        <div style={{ width: 240 }}><WeightField kind={currentConfig.weight} value={weightOf(current, setIndex)} onChange={(w) => onSetWeight(current.id, setIndex, w)} /></div>
        <button className="btn" onClick={onConfirmSetWeight}><Icon name="play" /> Start set</button>
        <button className="btn text" onClick={onStop}>Pause</button>
      </div>
    )
  }

  return (
    <div className="center-screen">
      <div className="muted">Exercise {auto.exerciseIndex + 1} of {steps.length}</div>
      {(auto.phase === 'REST' || auto.phase === 'COUNTDOWN') && !paused && <div className="small" style={{ color, fontWeight: 700, letterSpacing: 1 }}>UP NEXT</div>}
      <div className="title-lg">{current?.title}</div>
      {current?.imageUrl && <div style={{ width: 'min(420px, 100%)' }}><StepImage url={current.imageUrl} /></div>}
      <div className="muted">
        {currentConfig && currentConfig.sets > 1 && `Set ${setIndex + 1} of ${currentConfig.sets} · `}
        {side && `${side} · `}
        Rep {hangInSide} of {currentConfig?.reps ?? 1}
      </div>
      {current && currentConfig?.weight && <div className="muted">{formatTimedWeight(currentConfig.weight, weightOf(current, setIndex))}</div>}
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

/** Added weight (negative = assisted) or the load lifted, in lb. Blank = bodyweight / none. */
function WeightField({ kind, value, onChange }: { kind: WeightKind; value: number | null; onChange: (w: number | null) => void }) {
  const [text, setText] = useState(value == null ? '' : String(value))
  useEffect(() => {
    if ((text === '' ? null : Number(text)) !== value) setText(value == null ? '' : String(value))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [value])
  return (
    <Field label={kind === 'added' ? 'Added weight (lb, − = assisted)' : 'Weight (lb)'}>
      <input
        className="input"
        type="number"
        step={0.5}
        min={kind === 'load' ? 0 : undefined}
        value={text}
        placeholder={kind === 'added' ? 'Bodyweight' : '0'}
        onChange={(e) => {
          setText(e.target.value)
          const n = Number(e.target.value)
          if (e.target.value === '') onChange(null)
          else if (Number.isFinite(n) && (kind === 'added' || n >= 0)) onChange(n)
        }}
      />
    </Field>
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
