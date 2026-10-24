// Port of RoutineTaskViewModel's state handling: per-step set entries, superset
// rounds, pre-filling from the last completion, in-progress persistence, the
// automatic (interval) routine runner, and building step_completion data.

import { useCallback, useEffect, useRef, useState } from 'react'
import { parseJsonObject } from '@shared/json'
import type { Report, ReportSubmission, RoutineStep, StepCompletion, Task } from '@shared/models'
import { REPORT_DEFINITION_TABLES, REPORT_SUBMISSION_TABLES, getReports, getTodaysRoutineSubmissions } from '@shared/repos/reports'
import { ROUTINE_TABLES, completeRoutine, getLastStepCompletions, getRoutineSteps } from '@shared/repos/routines'
import { reportIdFromConfig } from '@shared/verification'
import { db, useQuery } from '../../lib/hooks'

export interface SetEntry {
  weight: number | null
  reps: number | null
  completed: boolean
  timerElapsed: number
  timerRunning: boolean
}

export interface StepState {
  step: RoutineStep
  sets: SetEntry[]
  activeSetIndex: number
}

export interface SupersetRound {
  groupId: string
  stepIds: string[]
  totalRounds: number
  currentRound: number
}

export type AutoPhase = 'IDLE' | 'COUNTDOWN' | 'WORK' | 'REST' | 'PAUSED' | 'COMPLETED'

export interface AutoProgress {
  phase: AutoPhase
  exerciseIndex: number
  rep: number
  secondsRemaining: number
}

const blankSet = (): SetEntry => ({ weight: null, reps: null, completed: false, timerElapsed: 0, timerRunning: false })

export function defaultSetCount(step: RoutineStep): number {
  const c = parseJsonObject(step.config)
  if (step.stepType === 'weight_reps' || step.stepType === 'just_reps') return Number(c.default_sets ?? 3) || 3
  if (step.stepType === 'timed_sets') return Number(c.reps ?? 1) || 1
  return 1
}

function prefill(steps: RoutineStep[], last: StepCompletion[]): { states: Record<string, StepState>; rounds: Record<string, SupersetRound> } {
  const byStep = new Map<string, StepCompletion[]>()
  for (const c of last) byStep.set(c.routineStepId, [...(byStep.get(c.routineStepId) ?? []), c])
  const states: Record<string, StepState> = {}
  for (const step of steps) {
    const count = defaultSetCount(step)
    const previous = byStep.get(step.id)
    // In a superset, sets are rounds: the count comes from default_sets.
    const n = step.supersetGroup ? count : Math.max(previous?.length ?? 0, count)
    states[step.id] = {
      step,
      activeSetIndex: 0,
      sets: Array.from({ length: n }, (_, i) => {
        const data = parseJsonObject(previous?.find((p) => p.setNumber === i + 1)?.data)
        const weight = typeof data.weight === 'number' ? data.weight : null
        const reps = typeof data.reps === 'number' && data.reps > 0 ? data.reps : null
        return { ...blankSet(), weight, reps }
      }),
    }
  }
  return { states, rounds: initialRounds(steps) }
}

function initialRounds(steps: RoutineStep[]): Record<string, SupersetRound> {
  const groups = new Map<string, RoutineStep[]>()
  for (const s of steps) if (s.supersetGroup) groups.set(s.supersetGroup, [...(groups.get(s.supersetGroup) ?? []), s])
  return Object.fromEntries(
    [...groups].map(([groupId, gs]) => [groupId, { groupId, stepIds: gs.map((s) => s.id), totalRounds: Math.max(...gs.map(defaultSetCount)), currentRound: 1 }]),
  )
}

interface Saved {
  steps: Record<string, { activeSetIndex: number; sets: { completed: boolean; timerElapsed: number; weight?: number; reps?: number }[] }>
  supersetRounds?: Record<string, { currentRound: number; totalRounds: number; stepIds: string[] }>
  autoRoutine?: { active: boolean; exerciseIndex: number; rep: number }
}

function restore(steps: RoutineStep[], saved: Saved | null): { states: Record<string, StepState>; rounds: Record<string, SupersetRound>; auto: AutoProgress | null } | null {
  if (!saved?.steps) return null
  // None of the saved ids match the current steps: stale.
  if (!Object.keys(saved.steps).some((id) => steps.some((s) => s.id === id))) return null
  const states: Record<string, StepState> = {}
  for (const step of steps) {
    const s = saved.steps[step.id]
    states[step.id] = s
      ? {
          step,
          activeSetIndex: s.activeSetIndex ?? 0,
          sets: (s.sets ?? []).map((x) => ({ ...blankSet(), completed: !!x.completed, timerElapsed: x.timerElapsed ?? 0, weight: x.weight ?? null, reps: x.reps && x.reps > 0 ? x.reps : null })),
        }
      : { step, activeSetIndex: 0, sets: Array.from({ length: defaultSetCount(step) }, blankSet) }
    if (states[step.id].sets.length === 0) states[step.id].sets = Array.from({ length: defaultSetCount(step) }, blankSet)
  }
  const rounds = saved.supersetRounds
    ? Object.fromEntries(Object.entries(saved.supersetRounds).map(([g, r]) => [g, { groupId: g, stepIds: r.stepIds ?? [], totalRounds: r.totalRounds ?? 1, currentRound: r.currentRound ?? 1 }]))
    : initialRounds(steps)
  const auto = saved.autoRoutine?.active ? { phase: 'PAUSED' as const, exerciseIndex: saved.autoRoutine.exerciseIndex, rep: saved.autoRoutine.rep, secondsRemaining: 0 } : null
  return { states, rounds, auto }
}

function serialize(states: Record<string, StepState>, rounds: Record<string, SupersetRound>, auto: AutoProgress): Saved {
  const out: Saved = {
    steps: Object.fromEntries(Object.entries(states).map(([id, s]) => [id, {
      activeSetIndex: s.activeSetIndex,
      sets: s.sets.map((e) => ({ completed: e.completed, timerElapsed: e.timerElapsed, ...(e.weight != null ? { weight: e.weight } : {}), ...(e.reps != null ? { reps: e.reps } : {}) })),
    }])),
  }
  if (Object.keys(rounds).length) {
    out.supersetRounds = Object.fromEntries(Object.entries(rounds).map(([g, r]) => [g, { currentRound: r.currentRound, totalRounds: r.totalRounds, stepIds: r.stepIds }]))
  }
  if (auto.phase !== 'IDLE' && auto.phase !== 'COMPLETED') out.autoRoutine = { active: true, exerciseIndex: auto.exerciseIndex, rep: auto.rep }
  return out
}

// ── Sounds (Android uses ToneGenerator DTMF tones) ───────────────────

let audio: AudioContext | null = null
function tone(freq: number, ms: number) {
  try {
    audio ??= new AudioContext()
    const osc = audio.createOscillator()
    const gain = audio.createGain()
    osc.frequency.value = freq
    gain.gain.value = 0.25
    osc.connect(gain).connect(audio.destination)
    osc.start()
    osc.stop(audio.currentTime + ms / 1000)
  } catch {
    // No audio device.
  }
}
const playTick = () => tone(700, 150)
const playBeep = () => tone(1400, 300)

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms))

export function useRoutineSession(task: Task) {
  const taskId = task.id
  const progressKey = `routineProgress:${taskId}`
  const { data: steps, loading: stepsLoading } = useQuery(() => getRoutineSteps(db, taskId), ROUTINE_TABLES, [taskId], [] as RoutineStep[])
  const { data: reports } = useQuery(() => getReports(db), REPORT_DEFINITION_TABLES, [], [] as Report[])
  const { data: submissions } = useQuery(() => getTodaysRoutineSubmissions(db, taskId), REPORT_SUBMISSION_TABLES, [taskId], {} as Record<string, ReportSubmission>)

  const [states, setStates] = useState<Record<string, StepState>>({})
  const [rounds, setRounds] = useState<Record<string, SupersetRound>>({})
  const [auto, setAuto] = useState<AutoProgress>({ phase: 'IDLE', exerciseIndex: 0, rep: 0, secondsRemaining: 0 })
  const [isCompleting, setIsCompleting] = useState(false)
  const [initialized, setInitialized] = useState(false)
  const cancelAuto = useRef<{ cancelled: boolean } | null>(null)
  const latest = useRef({ states, rounds, auto })
  latest.current = { states, rounds, auto }

  const isAutoRoutine = parseJsonObject(task.verificationConfig).execution_mode === 'automatic'

  // Initialise once steps arrive: restore an in-progress session, else pre-fill.
  useEffect(() => {
    if (initialized || stepsLoading || steps.length === 0) return
    let alive = true
    void (async () => {
      const restored = restore(steps, await window.api.kv.get(progressKey))
      if (!alive) return
      if (restored) {
        setStates(restored.states)
        setRounds(restored.rounds)
        if (restored.auto) setAuto(restored.auto)
      } else {
        if (await window.api.kv.get(progressKey)) await window.api.kv.set(progressKey, null)
        const init = prefill(steps, await getLastStepCompletions(db, taskId))
        if (!alive) return
        setStates(init.states)
        setRounds(init.rounds)
      }
      setInitialized(true)
    })()
    return () => {
      alive = false
    }
  }, [steps, stepsLoading, initialized, progressKey, taskId])

  // Steps added/removed mid-session (here or synced from another device).
  useEffect(() => {
    if (!initialized) return
    setStates((prev) => {
      const next: Record<string, StepState> = {}
      for (const step of steps) {
        next[step.id] = prev[step.id]
          ? { ...prev[step.id], step }
          : { step, activeSetIndex: 0, sets: Array.from({ length: defaultSetCount(step) }, blankSet) }
      }
      return next
    })
  }, [steps, initialized])

  // A report step is complete exactly when today's submission for it exists.
  useEffect(() => {
    setStates((prev) => {
      let changed = false
      const next = { ...prev }
      for (const [id, s] of Object.entries(prev)) {
        if (s.step.stepType !== 'report') continue
        const done = id in submissions
        if ((s.sets[0]?.completed ?? false) !== done) {
          next[id] = { ...s, sets: s.sets.map((e) => ({ ...e, completed: done })) }
          changed = true
        }
      }
      return changed ? next : prev
    })
  }, [submissions, states])

  // Persist progress on every change.
  useEffect(() => {
    if (!initialized || Object.keys(states).length === 0 || isCompleting) return
    void window.api.kv.set(progressKey, serialize(states, rounds, auto))
  }, [states, rounds, auto, initialized, progressKey, isCompleting])

  // Timed steps tick once a second.
  const anyTimerRunning = Object.values(states).some((s) => s.sets.some((e) => e.timerRunning))
  useEffect(() => {
    if (!anyTimerRunning) return
    const id = setInterval(() => {
      setStates((prev) => {
        const next = { ...prev }
        for (const [stepId, s] of Object.entries(prev)) {
          if (!s.sets.some((e) => e.timerRunning)) continue
          const target = Number(parseJsonObject(s.step.config).duration_seconds ?? 60)
          next[stepId] = {
            ...s,
            sets: s.sets.map((e) => {
              if (!e.timerRunning) return e
              const elapsed = e.timerElapsed + 1
              return elapsed >= target ? { ...e, timerElapsed: elapsed, timerRunning: false, completed: true } : { ...e, timerElapsed: elapsed }
            }),
          }
        }
        return next
      })
    }, 1000)
    return () => clearInterval(id)
  }, [anyTimerRunning])

  // Stop a running auto routine when leaving the screen; it resumes from where it was.
  useEffect(() => () => {
    if (cancelAuto.current) cancelAuto.current.cancelled = true
  }, [])

  const updateStep = useCallback((stepId: string, fn: (s: StepState) => StepState) => {
    setStates((prev) => (prev[stepId] ? { ...prev, [stepId]: fn(prev[stepId]) } : prev))
  }, [])

  const updateSet = (stepId: string, index: number, entry: SetEntry) =>
    updateStep(stepId, (s) => ({ ...s, sets: s.sets.map((e, i) => (i === index ? entry : e)) }))

  const actions = {
    updateSet,
    toggleCheckbox: (stepId: string) => updateStep(stepId, (s) => ({ ...s, sets: s.sets.map((e, i) => (i === 0 ? { ...e, completed: !e.completed } : e)) })),
    completeCurrentSet: (stepId: string) =>
      updateStep(stepId, (s) => {
        const idx = s.activeSetIndex
        const sets = s.sets.map((e, i) => (i === idx ? { ...e, completed: true } : e))
        return { ...s, sets, activeSetIndex: idx + 1 < sets.length ? idx + 1 : idx }
      }),
    goToSet: (stepId: string, index: number) => updateStep(stepId, (s) => (index >= 0 && index < s.sets.length ? { ...s, activeSetIndex: index } : s)),
    addSet: (stepId: string) =>
      updateStep(stepId, (s) => {
        const last = s.sets[s.sets.length - 1] ?? blankSet()
        const sets = [...s.sets, { ...last, completed: false, timerRunning: false, timerElapsed: 0 }]
        // If all previous sets were completed, move to the new one.
        return { ...s, sets, activeSetIndex: s.sets.every((e) => e.completed) ? sets.length - 1 : s.activeSetIndex }
      }),
    removeSet: (stepId: string) =>
      updateStep(stepId, (s) => (s.sets.length > 1 ? { ...s, sets: s.sets.slice(0, -1), activeSetIndex: Math.min(s.activeSetIndex, s.sets.length - 2) } : s)),
    startTimer: (stepId: string, index: number) =>
      updateStep(stepId, (s) => ({ ...s, sets: s.sets.map((e, i) => (i === index ? { ...e, timerRunning: true, timerElapsed: 0 } : e)) })),
    stopTimer: (stepId: string, index: number) =>
      updateStep(stepId, (s) => ({ ...s, sets: s.sets.map((e, i) => (i === index ? { ...e, timerRunning: false } : e)) })),
    advanceRound: (groupId: string) => {
      const r = latest.current.rounds[groupId]
      if (!r || r.currentRound >= r.totalRounds) return
      const setIndex = r.currentRound - 1
      for (const stepId of r.stepIds) {
        updateStep(stepId, (s) => ({ ...s, sets: s.sets.map((e, i) => (i === setIndex ? { ...e, completed: true } : e)) }))
      }
      setRounds((prev) => ({ ...prev, [groupId]: { ...r, currentRound: r.currentRound + 1 } }))
    },
    goToRound: (groupId: string, round: number) =>
      setRounds((prev) => (prev[groupId] && round >= 1 && round <= prev[groupId].totalRounds ? { ...prev, [groupId]: { ...prev[groupId], currentRound: round } } : prev)),
  }

  const allStepsCompleted = Object.keys(states).length > 0 && Object.values(states).every((s) => s.sets.every((e) => e.completed))

  const finish = async (onDone: () => void) => {
    if (isCompleting) return
    setIsCompleting(true)
    if (cancelAuto.current) cancelAuto.current.cancelled = true
    const stepData: Record<string, string[]> = {}
    for (const [stepId, s] of Object.entries(latest.current.states)) {
      const cfg = parseJsonObject(s.step.config)
      stepData[stepId] = s.sets.map((e) => {
        const json: Record<string, unknown> = {}
        switch (s.step.stepType) {
          case 'checkbox': json.completed = e.completed; break
          case 'weight_reps':
            if (e.weight != null) json.weight = e.weight
            if (e.reps != null) json.reps = e.reps
            break
          case 'just_reps': if (e.reps != null) json.reps = e.reps; break
          case 'timed': json.completed = e.completed; json.actual_seconds = e.timerElapsed; break
          case 'timed_sets': json.completed = e.completed; json.work_seconds = Number(cfg.work_seconds ?? 0); break
          case 'report':
            json.completed = e.completed
            if (submissions[stepId]) json.submission_id = submissions[stepId].id
            break
        }
        return JSON.stringify(json)
      })
    }
    try {
      await completeRoutine(db, taskId, stepData)
      await window.api.kv.set(progressKey, null)
      onDone()
    } finally {
      setIsCompleting(false)
    }
  }

  // ── Automatic routine ─────────────────────────────────────────────
  const startAuto = () => {
    if (cancelAuto.current && !cancelAuto.current.cancelled) return
    if (steps.length === 0) return
    let startExercise = 0
    let startRep = 0
    let allDone = true
    for (let i = 0; i < steps.length; i++) {
      const s = latest.current.states[steps[i].id]
      if (!s) continue
      const firstIncomplete = s.sets.findIndex((e) => !e.completed)
      if (firstIncomplete >= 0) {
        startExercise = i
        startRep = firstIncomplete
        allDone = false
        break
      }
    }
    if (allDone && Object.keys(latest.current.states).length > 0) {
      setAuto({ phase: 'COMPLETED', exerciseIndex: 0, rep: 0, secondsRemaining: 0 })
      return
    }
    const token = { cancelled: false }
    cancelAuto.current = token
    const countdown = Number(parseJsonObject(task.verificationConfig).countdown_seconds ?? 3)
    void (async () => {
      let first = true
      for (let ex = startExercise; ex < steps.length; ex++) {
        const step = steps[ex]
        const cfg = parseJsonObject(step.config)
        const reps = Number(cfg.reps ?? 1)
        const work = Number(cfg.work_seconds ?? 10)
        const rest = Number(cfg.rest_seconds ?? 20)
        for (let rep = ex === startExercise ? startRep : 0; rep < reps; rep++) {
          if (first) {
            first = false
            for (let s = countdown; s >= 1; s--) {
              if (token.cancelled) return
              setAuto({ phase: 'COUNTDOWN', exerciseIndex: ex, rep, secondsRemaining: s })
              if (s <= 2) playTick()
              await sleep(1000)
            }
            playBeep()
          }
          for (let s = work; s >= 1; s--) {
            if (token.cancelled) return
            setAuto({ phase: 'WORK', exerciseIndex: ex, rep, secondsRemaining: s })
            await sleep(1000)
          }
          if (token.cancelled) return
          updateStep(step.id, (st) => ({
            ...st,
            sets: st.sets.map((e, i) => (i === rep ? { ...e, completed: true, timerElapsed: work } : e)),
            activeSetIndex: Math.min(rep + 1, st.sets.length - 1),
          }))
          const lastRep = rep === reps - 1
          const lastExercise = ex === steps.length - 1
          if (!(lastRep && lastExercise)) {
            const nextEx = lastRep ? ex + 1 : ex
            const nextRep = lastRep ? 0 : rep + 1
            for (let s = rest; s >= 1; s--) {
              if (token.cancelled) return
              const inCountdown = s <= countdown
              setAuto({ phase: inCountdown ? 'COUNTDOWN' : 'REST', exerciseIndex: nextEx, rep: nextRep, secondsRemaining: s })
              if (inCountdown && s <= 2) playTick()
              await sleep(1000)
            }
            playBeep()
          }
        }
      }
      if (!token.cancelled) {
        setAuto({ phase: 'COMPLETED', exerciseIndex: 0, rep: 0, secondsRemaining: 0 })
        playBeep()
      }
    })()
  }

  const stopAuto = () => {
    if (cancelAuto.current) cancelAuto.current.cancelled = true
    setAuto((a) => ({ ...a, phase: 'PAUSED' }))
  }

  const reportSteps: Record<string, Report | null> = {}
  for (const s of steps) {
    if (s.stepType === 'report') reportSteps[s.id] = reports.find((r) => r.id === reportIdFromConfig(s.config)) ?? null
  }

  return {
    steps, stepsLoading, states, rounds, auto, isAutoRoutine, isCompleting, allStepsCompleted, initialized,
    reportSteps, submissions, actions, finish, startAuto, stopAuto,
  }
}
