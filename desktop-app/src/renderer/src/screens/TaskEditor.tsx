import { useEffect, useState } from 'react'
import type { Report } from '@shared/models'
import { REPORT_DEFINITION_TABLES, getReports } from '@shared/repos/reports'
import { windowsAppRef } from '@shared/repos/blocking'
import {
  EditableStep, TaskEditorState, emptyEditorState, loadEditorState, newEditableStep, saveTask, totalSteps,
  validateForSave, validateStep, verificationForType,
} from '@shared/taskEditor'
import { AppPicker, PickedApp } from '../components/AppPicker'
import { Icon } from '../components/Icon'
import { Chip, DayChips, ErrorText, Field, IconButton, Loading, NumberInput, Switch, TextInput, TopBar } from '../components/ui'
import { cleanError, db, useQuery } from '../lib/hooks'
import { useRouter } from '../lib/router'

const TASK_TYPES: [string, string, string][] = [
  ['simple', 'Simple', 'Tap to complete'],
  ['timed', 'Timed', 'Requires a timed session'],
  ['routine', 'Routine', 'Multi-step checklist'],
  ['workout', 'Workout', 'Exercise tracking with sets'],
  ['journal', 'Journal', 'Write a short entry'],
  ['shopping', 'Shopping List', 'Running list with location reminder'],
  ['report', 'Report', 'Answer a few quick questions'],
]

/** Port of TaskEditorScreen / TaskEditorViewModel: a 3-step wizard (2 for shopping lists). */
export function TaskEditorScreen({ taskId }: { taskId: string | null }) {
  const { back } = useRouter()
  const [s, setS] = useState<TaskEditorState | null>(taskId ? null : emptyEditorState())
  const [step, setStep] = useState(0)
  const [error, setError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)
  const { data: reports } = useQuery(() => getReports(db), REPORT_DEFINITION_TABLES, [], [] as Report[])

  useEffect(() => {
    if (!taskId) return
    loadEditorState(db, taskId).then(setS).catch((e) => setError(cleanError(e)))
  }, [taskId])

  if (!s) return (<><TopBar title="Edit Task" />{error ? <div className="err-text">{error}</div> : <Loading />}</>)

  const update = (patch: Partial<TaskEditorState>) => {
    setS({ ...s, ...patch })
    setError(null)
  }
  const steps = totalSteps(s)
  const isLast = step === steps - 1

  const next = () => {
    const err = validateStep(s, step)
    if (err) return setError(err)
    setStep(step + 1)
  }
  const save = async () => {
    const err = validateForSave(s, reports)
    if (err) return setError(err)
    setSaving(true)
    try {
      await saveTask(db, s)
      await window.api.blocking.reevaluate()
      back()
    } catch (e) {
      setError(cleanError(e))
    } finally {
      setSaving(false)
    }
  }

  return (
    <>
      <TopBar title={`${s.editingTaskId ? 'Edit Task' : 'New Task'} — Step ${step + 1} of ${steps}`} />
      <StepIndicator current={step} total={steps} />
      <div className="stack lg" style={{ marginTop: 16 }}>
        {step === 0 && <BasicsStep s={s} update={update} />}
        {step === 1 && <ConfigStep s={s} update={update} reports={reports} />}
        {step === 2 && <ScheduleStep s={s} update={update} />}
        <ErrorText error={error} />
        <div className="row between" style={{ marginTop: 8 }}>
          <button className="btn outlined" onClick={() => (step > 0 ? setStep(step - 1) : back())}>{step > 0 ? 'Back' : 'Cancel'}</button>
          {isLast ? (
            <button className="btn" disabled={saving} onClick={() => void save()}>{saving ? 'Saving…' : 'Save'}</button>
          ) : (
            <button className="btn" onClick={next}>Next</button>
          )}
        </div>
      </div>
    </>
  )
}

function StepIndicator({ current, total }: { current: number; total: number }) {
  return (
    <div className="row" style={{ gap: 8 }}>
      {Array.from({ length: total }, (_, i) => (
        <div key={i} className="row grow" style={{ gap: 8 }}>
          <div className={`step-dot ${i === current ? 'active' : i < current ? 'done' : ''}`}>{i + 1}</div>
          {i < total - 1 && <div className="step-line" />}
        </div>
      ))}
    </div>
  )
}

type StepProps = { s: TaskEditorState; update: (p: Partial<TaskEditorState>) => void }

function BasicsStep({ s, update }: StepProps) {
  return (
    <>
      <Field label="Task Title"><TextInput value={s.title} onChange={(title) => update({ title })} autoFocus /></Field>
      <Field label="Description (optional)"><TextInput value={s.description} onChange={(description) => update({ description })} multiline rows={2} /></Field>
      <div className="section-label">Task Type</div>
      <div className="grid2">
        {TASK_TYPES.map(([value, label, desc]) => (
          <div
            key={value}
            className={`card clickable ${s.taskType === value ? 'selected' : ''}`}
            onClick={() => update({ taskType: value, verificationType: verificationForType(value, s.verificationType) })}
          >
            <div className="title-sm">{label}</div>
            <div className="small muted">{desc}</div>
          </div>
        ))}
      </div>
    </>
  )
}

function ConfigStep({ s, update, reports }: StepProps & { reports: Report[] }) {
  switch (s.taskType) {
    case 'timed': return <TimedConfig s={s} update={update} />
    case 'routine':
    case 'workout': return <RoutineConfig s={s} update={update} reports={reports} />
    case 'journal':
      return (
        <div className="card">
          <div className="title-sm">Journal entry</div>
          <div className="small muted">Completing this task opens a screen to write a short journal entry. Entries are saved and viewable from Journal in the sidebar.</div>
        </div>
      )
    case 'shopping': return <ShoppingConfig s={s} update={update} />
    case 'report': return <ReportConfig s={s} update={update} reports={reports} />
    default: return <SimpleConfig s={s} update={update} />
  }
}

function TimedConfig({ s, update }: StepProps) {
  return (
    <>
      <div className="section-label">Session Duration</div>
      <div className="grid2">
        <Field label="Minutes"><NumberInput value={Math.floor(s.durationSeconds / 60)} onChange={(m) => update({ durationSeconds: Math.max(1, m * 60 + (s.durationSeconds % 60)) })} /></Field>
        <Field label="Seconds"><NumberInput value={s.durationSeconds % 60} max={59} onChange={(sec) => update({ durationSeconds: Math.max(1, Math.floor(s.durationSeconds / 60) * 60 + sec) })} /></Field>
      </div>
    </>
  )
}

function SimpleConfig({ s, update }: StepProps) {
  return (
    <>
      <div className="section-label">Verification Method</div>
      <div className="chips">
        {[['manual', 'Manual'], ['gps', 'GPS Location'], ['meditation', 'Meditation']].map(([v, l]) => (
          <Chip key={v} selected={s.verificationType === v} onClick={() => update({ verificationType: v })}>{l}</Chip>
        ))}
      </div>
      {s.verificationType === 'gps' && (
        <>
          <LocationFields s={s} update={update} title="GPS Location" />
          <div className="section-label">Completes When</div>
          <div className="chips">
            <Chip selected={s.gpsMode === 'enter'} onClick={() => update({ gpsMode: 'enter' })}>I arrive</Chip>
            <Chip selected={s.gpsMode === 'leave'} onClick={() => update({ gpsMode: 'leave' })}>I leave</Chip>
          </div>
          {s.gpsMode === 'enter' && (
            <Field label="Minimum stay (minutes)" help="0 = complete on arrival">
              <NumberInput value={s.gpsMinDurationM} onChange={(gpsMinDurationM) => update({ gpsMinDurationM })} />
            </Field>
          )}
        </>
      )}
      {s.verificationType === 'meditation' && (
        <>
          <div className="section-label">Meditation Settings</div>
          <div className="grid2">
            <Field label="Duration (minutes)"><NumberInput value={Math.round(s.meditationSeconds / 60)} min={1} onChange={(m) => update({ meditationSeconds: Math.max(60, m * 60) })} /></Field>
            <Field label="Short sit (minutes, 0 = off)"><NumberInput value={Math.round(s.shortSitSeconds / 60)} onChange={(m) => update({ shortSitSeconds: m * 60 })} /></Field>
          </div>
          <div className="section-label">Companion apps</div>
          <div className="small muted">Time spent in these Windows apps counts toward the meditation. Companion apps set up on your phone are kept as they are.</div>
          <AppPicker
            selected={s.companionApps.map((a) => ({ exe: String(a.exe), name: String(a.name ?? a.exe), path: (a.path as string) ?? null }))}
            onToggle={(app: PickedApp) => {
              const has = s.companionApps.some((a) => a.exe === app.exe)
              update({
                companionApps: has
                  ? s.companionApps.filter((a) => a.exe !== app.exe)
                  : [...s.companionApps, { ...windowsAppRef(app.exe, app.name), ...(app.path ? { path: app.path } : {}) }],
              })
            }}
          />
        </>
      )}
    </>
  )
}

/** Lat/lng/radius with "use current location" from Windows location services. */
function LocationFields({ s, update, title, help }: StepProps & { title: string; help?: string }) {
  const [locating, setLocating] = useState(false)
  const [locError, setLocError] = useState<string | null>(null)
  const useCurrent = async () => {
    setLocating(true)
    setLocError(null)
    const status = await window.api.location.refresh()
    setLocating(false)
    if (status.lastFix && status.access === 'allowed') {
      update({ latitude: status.lastFix.latitude.toFixed(6), longitude: status.lastFix.longitude.toFixed(6) })
    } else setLocError(status.error ?? 'Location unavailable')
  }
  const lat = Number(s.latitude)
  const lng = Number(s.longitude)
  return (
    <>
      <div className="section-label">{title}</div>
      {help && <div className="small muted">{help}</div>}
      <div className="grid2">
        <Field label="Latitude"><TextInput value={s.latitude} onChange={(latitude) => update({ latitude })} invalid={s.latitude !== '' && !Number.isFinite(lat)} /></Field>
        <Field label="Longitude"><TextInput value={s.longitude} onChange={(longitude) => update({ longitude })} invalid={s.longitude !== '' && !Number.isFinite(lng)} /></Field>
      </div>
      <div className="row wrap">
        <button className="btn tonal sm" onClick={() => void useCurrent()} disabled={locating}><Icon name="place" size="sm" /> {locating ? 'Locating…' : 'Use current location'}</button>
        <a className="btn text sm" target="_blank" rel="noreferrer"
          href={s.latitude && s.longitude && Number.isFinite(lat) && Number.isFinite(lng) ? `https://www.openstreetmap.org/?mlat=${lat}&mlon=${lng}#map=17/${lat}/${lng}` : 'https://www.openstreetmap.org/'}>
          Look up on a map
        </a>
        {locError && <span className="small err-text">{locError}</span>}
      </div>
      <Field label="Radius (meters)"><NumberInput value={s.radiusMeters} min={10} onChange={(radiusMeters) => update({ radiusMeters: Math.max(10, radiusMeters) })} /></Field>
    </>
  )
}

function ShoppingConfig({ s, update }: StepProps) {
  return (
    <>
      <div className="card">
        <div className="title-sm">How shopping lists work</div>
        <div className="small muted">This list never completes — add items anytime and check them off as you buy them. When you arrive at the reminder location below with at least one item on the list, you'll get a notification.</div>
      </div>
      <LocationFields s={s} update={update} title="Reminder Location" help="E.g. the subway stop on your way home." />
      <div className="section-label">Reminder Window</div>
      <div className="small muted">Only notify on these days and between these times. At most one reminder per window.</div>
      <DayChips selected={s.notifyDays} onToggle={(d) => update({ notifyDays: toggle(s.notifyDays, d) })} />
      <div className="grid2">
        <Field label="From"><TimeInput value={s.notifyStart} onChange={(notifyStart) => update({ notifyStart })} /></Field>
        <Field label="Until"><TimeInput value={s.notifyEnd} onChange={(notifyEnd) => update({ notifyEnd })} /></Field>
      </div>
    </>
  )
}

function ReportConfig({ s, update, reports }: StepProps & { reports: Report[] }) {
  return (
    <>
      <ReportPicker value={s.reportId} reports={reports} onChange={(reportId) => update({ reportId })} />
      <div className="small muted">Completing this task asks each metric in the report. Several tasks or routine steps can share one report — answers feed the same history.</div>
    </>
  )
}

function ReportPicker({ value, reports, onChange }: { value: string | null; reports: Report[]; onChange: (id: string) => void }) {
  if (reports.length === 0) return <div className="small err-text">No reports yet — create one under Reports first.</div>
  return (
    <Field label="Report">
      <select className="input" value={value ?? ''} onChange={(e) => onChange(e.target.value)}>
        <option value="" disabled>Choose a report…</option>
        {reports.map((r) => <option key={r.id} value={r.id}>{r.name} ({r.metrics.length} metric{r.metrics.length === 1 ? '' : 's'})</option>)}
      </select>
    </Field>
  )
}

function RoutineConfig({ s, update, reports }: StepProps & { reports: Report[] }) {
  const [forSuperset, setForSuperset] = useState<string[]>([])
  const isWorkout = s.taskType === 'workout'
  const steps = s.routineSteps
  const setSteps = (routineSteps: EditableStep[]) => update({ routineSteps })
  const patch = (id: string, p: Partial<EditableStep>) => setSteps(steps.map((st) => (st.id === id ? { ...st, ...p } : st)))
  const move = (from: number, to: number) => {
    if (to < 0 || to >= steps.length) return
    const next = [...steps]
    const [item] = next.splice(from, 1)
    next.splice(to, 0, item)
    setSteps(next)
  }
  const addStep = () => setSteps([...steps, { ...newEditableStep(), stepType: isWorkout ? 'weight_reps' : 'checkbox' }])
  const types = isWorkout
    ? [['weight_reps', 'Weight & Reps'], ['just_reps', 'Reps Only'], ['timed', 'Timed'], ['checkbox', 'Checkbox']]
    : [['checkbox', 'Checkbox'], ['timed', 'Timed'], ['weight_reps', 'Weight & Reps'], ['just_reps', 'Reps Only'], ['report', 'Report']]

  return (
    <>
      <div className="row between">
        <div className="title-sm">{isWorkout ? 'Exercises' : 'Steps'}</div>
        <div className="row">
          {forSuperset.length >= 2 && (
            <button className="btn text sm" onClick={() => {
              const group = crypto.randomUUID().slice(0, 8)
              setSteps(steps.map((st) => (forSuperset.includes(st.id) ? { ...st, supersetGroup: group } : st)))
              setForSuperset([])
            }}>Group as Superset</button>
          )}
          <button className="btn text sm" onClick={addStep}><Icon name="add" size="sm" /> Add</button>
        </div>
      </div>
      {steps.length === 0 && (
        <div className="card center stack sm" style={{ alignItems: 'center' }}>
          <div className="muted">{isWorkout ? 'No exercises yet' : 'No steps yet'}</div>
          <button className="btn outlined" onClick={addStep}><Icon name="add" /> {isWorkout ? 'Add Exercise' : 'Add Step'}</button>
        </div>
      )}
      {steps.map((st, i) => (
        <div key={st.id} className="card stack sm" style={st.supersetGroup ? { background: 'color-mix(in srgb, var(--tertiary-container) 30%, var(--surface))' } : undefined}>
          <div className="row">
            <input type="checkbox" title="Select for superset" checked={forSuperset.includes(st.id)} onChange={() => setForSuperset(toggle(forSuperset, st.id))} />
            <div className="grow"><TextInput value={st.title} onChange={(title) => patch(st.id, { title })} placeholder={isWorkout ? 'Exercise name' : 'Step name'} /></div>
            <IconButton icon="up" title="Move up" small disabled={i === 0} onClick={() => move(i, i - 1)} />
            <IconButton icon="down" title="Move down" small disabled={i === steps.length - 1} onClick={() => move(i, i + 1)} />
            <IconButton icon="delete" title="Remove" small onClick={() => setSteps(steps.filter((x) => x.id !== st.id))} />
          </div>
          <TextInput value={st.description} onChange={(description) => patch(st.id, { description })} placeholder="Notes (optional) — form cues, weights to use…" multiline rows={1} />
          <div className="grid2">
            <Field label="Type">
              <select className="input" value={st.stepType} onChange={(e) => patch(st.id, { stepType: e.target.value })}>
                {types.map(([v, l]) => <option key={v} value={v}>{l}</option>)}
                {!types.some(([v]) => v === st.stepType) && <option value={st.stepType}>{st.stepType}</option>}
              </select>
            </Field>
            {(st.stepType === 'weight_reps' || st.stepType === 'just_reps') && (
              <Field label="Default sets"><NumberInput value={st.defaultSets} min={1} max={20} onChange={(defaultSets) => patch(st.id, { defaultSets })} /></Field>
            )}
            {st.stepType === 'timed' && (
              <Field label="Duration (seconds)"><NumberInput value={st.durationSeconds} min={1} onChange={(durationSeconds) => patch(st.id, { durationSeconds })} /></Field>
            )}
          </div>
          {st.stepType === 'report' && <ReportPicker value={st.reportId} reports={reports} onChange={(reportId) => patch(st.id, { reportId })} />}
          {st.supersetGroup && (
            <div className="row small" style={{ color: 'var(--tertiary)' }}>
              In superset
              <button className="btn text sm" onClick={() => setSteps(steps.map((x) => (x.supersetGroup === st.supersetGroup ? { ...x, supersetGroup: null } : x)))}>Ungroup</button>
            </div>
          )}
        </div>
      ))}
    </>
  )
}

function ScheduleStep({ s, update }: StepProps) {
  const custom = s.recurrenceType === 'monthly' || s.recurrenceType === 'interval'
  return (
    <>
      <div className="section-label">Recurrence</div>
      <div className="chips">
        {([[null, 'One-time'], ['daily', 'Daily'], ['weekly', 'Weekly']] as const).map(([v, l]) => (
          <Chip key={l} selected={s.recurrenceType === v} onClick={() => update({ recurrenceType: v })}>{l}</Chip>
        ))}
        {custom && <Chip selected onClick={() => undefined}>{s.recurrenceType === 'monthly' ? 'Monthly' : 'Interval'} (kept as is)</Chip>}
      </div>
      {s.recurrenceType === 'weekly' && (
        <>
          <div className="section-label">Days</div>
          <DayChips selected={s.weeklyDays} onToggle={(d) => update({ weeklyDays: toggle(s.weeklyDays, d) })} />
        </>
      )}
      <div className="section-label">Availability Window</div>
      <div className="small muted">When this task can be completed and when it becomes overdue</div>
      <div className="grid2">
        <Field label="Available from"><TimeInput value={s.availableFrom} onChange={(availableFrom) => update({ availableFrom })} /></Field>
        <Field label="Due at"><TimeInput value={s.dueAt} onChange={(dueAt) => update({ dueAt })} /></Field>
      </div>
      <div className="section-label">Blocking</div>
      <div className="row top">
        <div className="grow">
          <div>Use as blocking condition</div>
          <div className="small muted">Makes this task available to blocking rules. Add it to a rule under Blocking rules to block apps and websites until it's done.</div>
        </div>
        <Switch checked={s.isBlockingCondition} onChange={(isBlockingCondition) => update({ isBlockingCondition })} label="Use as blocking condition" />
      </div>
      {s.isBlockingCondition && (
        <>
          <div className="row top">
            <div className="grow">
              <div>Only block when at home</div>
              <div className="small muted">Skip this task's blocking when you're away from your designated home location (e.g. on vacation)</div>
            </div>
            <Switch checked={s.homeOnlyBlocking} onChange={(homeOnlyBlocking) => update({ homeOnlyBlocking })} label="Only block when at home" />
          </div>
          <div>Blocking days</div>
          <div className="small muted">Days this task enforces blocking. Leave empty to block every day; on other days the task is still completable but doesn't block.</div>
          <DayChips selected={s.blockingDaysOfWeek} onToggle={(d) => update({ blockingDaysOfWeek: toggle(s.blockingDaysOfWeek, d) })} />
        </>
      )}
    </>
  )
}

function TimeInput({ value, onChange }: { value: string; onChange: (v: string) => void }) {
  const padded = /^\d:\d{2}$/.test(value) ? `0${value}` : value
  return (
    <div className="row">
      <input className="input" type="time" value={padded} onChange={(e) => onChange(e.target.value)} />
      {value && <IconButton icon="close" title="Clear" small onClick={() => onChange('')} />}
    </div>
  )
}

function toggle(list: string[], value: string): string[] {
  return list.includes(value) ? list.filter((v) => v !== value) : [...list, value]
}

