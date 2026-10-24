// Ports of service/DurationTimerManager.kt and service/MeditationTimerManager.kt.
// Timers live in the main process so they keep running with the window hidden;
// state is persisted so a restart restores them (paused).

import type { DurationTimerState, MeditationTimerState, TimerStates } from '@shared/ipc'
import { completeTask, updateTodaysVerificationData } from '@shared/repos/tasks'
import { todayKey } from '@shared/time'
import type { MeditationConfig } from '@shared/verification'
import { log } from './debugLog'
import { mainDb } from './powersync'
import { playChime } from './sound'
import { getValue, setValue } from './store'

type Persisted<T> = T & { date: string; bellIntervalSeconds?: number; companionApps?: string[] }

interface Running {
  /** Wall-clock ms when the timer last (re)started ticking. */
  startedAt: number
}

const listeners = new Set<(s: TimerStates) => void>()
const duration = new Map<string, DurationTimerState & Running>()
const meditation = new Map<string, MeditationTimerState & Running & { bellIntervalSeconds: number; companionApps: string[] }>()
let ticker: NodeJS.Timeout | null = null
let currentForeground: string | null = null

export function onTimerStates(listener: (s: TimerStates) => void): () => void {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

export function getTimerStates(): TimerStates {
  const strip = <T extends { startedAt: number }>(s: T) => {
    const { startedAt: _s, ...rest } = s
    return rest
  }
  return {
    duration: Object.fromEntries([...duration].map(([id, s]) => [id, { ...strip(s), elapsedSeconds: elapsedOf(s) }])),
    meditation: Object.fromEntries(
      [...meditation].map(([id, s]) => {
        const { bellIntervalSeconds: _b, companionApps: _c, ...rest } = strip(s)
        return [id, { ...rest, elapsedSeconds: elapsedOf(s) }]
      }),
    ),
  }
}

function emit(): void {
  const states = getTimerStates()
  listeners.forEach((l) => l(states))
}

function elapsedOf(s: { elapsedSeconds: number; running: boolean; startedAt: number }): number {
  return s.running ? s.elapsedSeconds + Math.floor((Date.now() - s.startedAt) / 1000) : s.elapsedSeconds
}

function ensureTicker(): void {
  if (ticker) return
  ticker = setInterval(tick, 1000)
}

// ── Duration ─────────────────────────────────────────────────────────

export function startDuration(taskId: string, taskTitle: string, targetSeconds: number): void {
  if (duration.has(taskId)) return
  const saved = persistedDuration()[taskId]
  const elapsed = saved && saved.date === todayKey() ? saved.elapsedSeconds : 0
  duration.set(taskId, { taskId, taskTitle, targetSeconds, elapsedSeconds: elapsed, running: true, startedAt: Date.now() })
  persistDuration()
  log('duration', `Started timer for '${taskTitle}' (target=${targetSeconds}s, resumed=${elapsed}s)`)
  ensureTicker()
  emit()
}

export function pauseDuration(taskId: string): void {
  const s = duration.get(taskId)
  if (!s?.running) return
  duration.set(taskId, { ...s, elapsedSeconds: elapsedOf(s), running: false })
  persistDuration()
  emit()
}

export function resumeDuration(taskId: string): void {
  const s = duration.get(taskId)
  if (!s || s.running) return
  duration.set(taskId, { ...s, running: true, startedAt: Date.now() })
  persistDuration()
  emit()
}

export function cancelDuration(taskId: string): void {
  duration.delete(taskId)
  persistDuration()
  emit()
}

async function completeDuration(taskId: string, actualSeconds: number): Promise<void> {
  const s = duration.get(taskId)
  duration.delete(taskId)
  persistDuration()
  emit()
  const data = JSON.stringify({ actual_seconds: actualSeconds })
  log('duration', `Timer complete for '${s?.taskTitle ?? taskId}' (${actualSeconds}s)`, data)
  await completeTask(mainDb, taskId, data)
}

function persistedDuration(): Record<string, Persisted<DurationTimerState>> {
  return getValue('durationTimers', {})
}

function persistDuration(): void {
  const out: Record<string, Persisted<DurationTimerState>> = {}
  for (const [id, s] of duration) {
    out[id] = { taskId: id, taskTitle: s.taskTitle, targetSeconds: s.targetSeconds, elapsedSeconds: elapsedOf(s), running: false, date: todayKey() }
  }
  setValue('durationTimers', out)
}

// ── Meditation ───────────────────────────────────────────────────────

/**
 * Start an in-app session. Takes over from companion tracking (auto-started
 * for tasks with companion apps), carrying today's accumulated time over.
 */
export function startMeditationInApp(taskId: string, taskTitle: string, config: MeditationConfig, shortSitIntent: boolean): void {
  const existing = meditation.get(taskId)
  if (existing && existing.method !== 'companion_app') return
  if (shortSitIntent && config.shortSitSeconds <= 0) return
  const saved = persistedMeditation()[taskId]
  const elapsed = existing ? elapsedOf(existing) : saved && saved.date === todayKey() ? saved.elapsedSeconds : 0
  const pauses = existing ? 0 : saved && saved.date === todayKey() ? saved.pauses : 0
  meditation.set(taskId, {
    taskId, taskTitle, targetSeconds: config.durationSeconds, elapsedSeconds: elapsed, running: true, pauses,
    method: 'in_app_timer', companionApp: null, shortSitSeconds: config.shortSitSeconds, shortSitIntent,
    shortSitBanked: saved?.date === todayKey() ? saved.shortSitBanked : false,
    startedAt: Date.now(), bellIntervalSeconds: config.bellIntervalSeconds, companionApps: [],
  })
  persistMeditation()
  log('meditation', `Started in-app meditation for '${taskTitle}' (target=${config.durationSeconds}s, shortSit=${config.shortSitSeconds}s, intent=${shortSitIntent ? 'short' : 'full'})`)
  ensureTicker()
  emit()
}

/** Track foreground time in the task's companion apps toward its duration. */
export function startCompanionTracking(taskId: string, taskTitle: string, config: MeditationConfig): void {
  if (meditation.has(taskId) || config.companionApps.length === 0) return
  const saved = persistedMeditation()[taskId]
  const elapsed = saved && saved.date === todayKey() ? saved.elapsedSeconds : 0
  meditation.set(taskId, {
    taskId, taskTitle, targetSeconds: config.durationSeconds, elapsedSeconds: elapsed, running: false, pauses: 0,
    method: 'companion_app', companionApp: null, shortSitSeconds: 0, shortSitIntent: false, shortSitBanked: false,
    startedAt: Date.now(), bellIntervalSeconds: 0, companionApps: config.companionApps,
  })
  persistMeditation()
  log('companion', `Started companion tracking for '${taskTitle}'`, config.companionApps.join(', '))
  ensureTicker()
  onForegroundForCompanions(currentForeground)
  emit()
}

export function meditationCompanionApps(taskId: string): string[] | null {
  return meditation.get(taskId)?.companionApps ?? null
}

export function hasMeditationTimer(taskId: string): { method: string } | null {
  const s = meditation.get(taskId)
  return s ? { method: s.method } : null
}

export function pauseMeditation(taskId: string): void {
  const s = meditation.get(taskId)
  if (!s?.running) return
  const paused = { ...s, elapsedSeconds: elapsedOf(s), running: false, pauses: s.pauses + 1 }
  meditation.set(taskId, paused)
  persistMeditation()
  syncBankedCompletion(paused)
  emit()
}

export function resumeMeditation(taskId: string): void {
  const s = meditation.get(taskId)
  if (!s || s.running) return
  meditation.set(taskId, { ...s, running: true, startedAt: Date.now() })
  persistMeditation()
  emit()
}

/** End an in-app session at or past its short-sit threshold, recording it as done. */
export function finishMeditationEarly(taskId: string): boolean {
  const s = meditation.get(taskId)
  if (!s) return false
  const elapsed = elapsedOf(s)
  if (s.shortSitSeconds <= 0 || elapsed < s.shortSitSeconds) return false
  void completeMeditation(taskId, elapsed)
  return true
}

export function cancelMeditation(taskId: string): void {
  meditation.delete(taskId)
  persistMeditation()
  emit()
}

/** Called by the enforcer's foreground watcher. */
export function onForegroundForCompanions(exe: string | null): void {
  currentForeground = exe
  let changed = false
  for (const [id, s] of meditation) {
    if (s.method !== 'companion_app') continue
    const inCompanion = !!exe && s.companionApps.includes(exe)
    if (inCompanion && !s.running) {
      log('companion', `Companion app opened: ${exe}`, JSON.stringify({ taskId: id, elapsed: s.elapsedSeconds, target: s.targetSeconds }))
      meditation.set(id, { ...s, running: true, startedAt: Date.now(), companionApp: exe })
      changed = true
    } else if (!inCompanion && s.running) {
      const elapsed = elapsedOf(s)
      log('companion', `Companion app left foreground — paused at ${elapsed}s`)
      meditation.set(id, { ...s, elapsedSeconds: elapsed, running: false })
      changed = true
    }
  }
  if (changed) {
    persistMeditation()
    emit()
  }
}

async function completeMeditation(taskId: string, actualSeconds: number): Promise<void> {
  const s = meditation.get(taskId)
  meditation.delete(taskId)
  persistMeditation()
  emit()
  const data = verificationData(s, actualSeconds)
  log('meditation', `Meditation complete for '${s?.taskTitle ?? taskId}' (${actualSeconds}s, method=${s?.method})`, data)
  await completeTask(mainDb, taskId, data)
  // A banked short sit already wrote today's completion (completeTask was a
  // no-op) — overwrite its proof with the final tally.
  if (s?.shortSitBanked) await updateTodaysVerificationData(mainDb, taskId, data)
}

function verificationData(s: MeditationTimerState | undefined, actualSeconds: number): string {
  const method = s?.method ?? 'in_app_timer'
  const out: Record<string, unknown> = { method }
  if (method === 'in_app_timer') out.pauses = s?.pauses ?? 0
  else out.app = s?.companionApp ?? 'unknown'
  out.actual_seconds = actualSeconds
  if (s && s.shortSitSeconds > 0) {
    out.target_seconds = s.targetSeconds
    out.short_sit = actualSeconds < s.targetSeconds
  }
  return JSON.stringify(out)
}

function syncBankedCompletion(s: MeditationTimerState): void {
  if (!s.shortSitBanked) return
  void updateTodaysVerificationData(mainDb, s.taskId, verificationData(s, s.elapsedSeconds))
}

function persistedMeditation(): Record<string, Persisted<MeditationTimerState>> {
  return getValue('meditationTimers', {})
}

function persistMeditation(): void {
  const out: Record<string, Persisted<MeditationTimerState>> = {}
  for (const [id, s] of meditation) {
    const { startedAt: _s, ...rest } = s
    out[id] = { ...rest, elapsedSeconds: elapsedOf(s), running: false, date: todayKey() }
  }
  setValue('meditationTimers', out)
}

// ── Tick ─────────────────────────────────────────────────────────────

let tickCount = 0

function tick(): void {
  tickCount++
  for (const [id, s] of duration) {
    if (!s.running) continue
    const elapsed = elapsedOf(s)
    if (elapsed >= s.targetSeconds) void completeDuration(id, elapsed)
  }
  for (const [id, s] of meditation) {
    if (!s.running) continue
    const elapsed = elapsedOf(s)
    if (elapsed >= s.targetSeconds) {
      if (s.method === 'in_app_timer') playChime()
      void completeMeditation(id, elapsed)
      continue
    }
    if (s.method !== 'in_app_timer') continue
    if (s.shortSitSeconds > 0 && !s.shortSitBanked && elapsed >= s.shortSitSeconds) {
      const banked = { ...s, shortSitBanked: true }
      meditation.set(id, banked)
      persistMeditation()
      // Record today's completion the moment the threshold is reached, so it
      // counts even if the session is later abandoned.
      const data = verificationData(banked, elapsed)
      void completeTask(mainDb, id, data)
      log('meditation', `Short sit banked for '${s.taskTitle}' (${elapsed}s)`, data)
      if (s.shortSitIntent) playChime()
    } else if (s.bellIntervalSeconds > 0 && elapsed > 0 && elapsed % s.bellIntervalSeconds === 0) {
      playChime()
    }
  }
  if (tickCount % 10 === 0) {
    if (duration.size) persistDuration()
    if (meditation.size) persistMeditation()
  }
  if (duration.size || meditation.size) emit()
}

/**
 * Restore persisted timers (paused) after a restart. Timers from a previous
 * day are dropped — the cumulative target is per day.
 */
export function restoreTimers(): void {
  const today = todayKey()
  for (const [id, s] of Object.entries(persistedDuration())) {
    if (s.date !== today || s.targetSeconds <= 0) continue
    if (s.elapsedSeconds >= s.targetSeconds) {
      void completeTask(mainDb, id, JSON.stringify({ actual_seconds: s.elapsedSeconds }))
      continue
    }
    duration.set(id, { ...s, running: false, startedAt: Date.now() })
  }
  for (const [id, s] of Object.entries(persistedMeditation())) {
    if (s.date !== today || s.targetSeconds <= 0) continue
    meditation.set(id, {
      ...s, running: false, startedAt: Date.now(),
      bellIntervalSeconds: s.bellIntervalSeconds ?? 0, companionApps: s.companionApps ?? [],
    })
  }
  persistDuration()
  persistMeditation()
  if (duration.size || meditation.size) ensureTicker()
  emit()
}

export function persistAllTimers(): void {
  persistDuration()
  persistMeditation()
}
