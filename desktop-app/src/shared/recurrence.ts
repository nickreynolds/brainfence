// Port of domain/recurrence/RecurrenceEngine.kt and TimeGatePhase.kt.
// Must stay behaviourally identical to the Android client: both devices
// evaluate the same synced tasks and must agree on what's due.

import { parseInstant, parseJsonObject } from './json'
import {
  LocalDate, DayIndex, compareDates, dayOfWeek, localDateOf, parseDay, parseTaskTime,
  plusDays, plusMonths, sameDate, secondsOfDay, startOfDay, toEpochDay,
} from './time'

export type TimeGatePhase = 'BEFORE_START' | 'ACTIVE' | 'PAST_DUE'

export type OccurrenceStatus =
  | { kind: 'Due' }
  | { kind: 'Completed' }
  | { kind: 'NotDue' }
  | { kind: 'Upcoming'; nextDueAt: Date }

/**
 * Which phase a task is in based on its availability window, or null when the
 * task has no time constraints.
 */
export function computeTaskPhase(
  availableFrom: string | null | undefined,
  dueAt: string | null | undefined,
  currentTime: Date,
): TimeGatePhase | null {
  const start = parseTaskTime(availableFrom)
  const end = parseTaskTime(dueAt)
  if (start == null && end == null) return null
  const now = secondsOfDay(currentTime)
  if (start != null && now < start) return 'BEFORE_START'
  if (end != null && now >= end) return 'PAST_DUE'
  return 'ACTIVE'
}

export function computeOccurrenceStatus(
  recurrenceType: string | null,
  recurrenceConfig: string,
  availableFrom: string | null,
  dueAt: string | null,
  lastCompletionAt: Date | null,
  currentTime: Date,
): OccurrenceStatus {
  if (computeTaskPhase(availableFrom, dueAt, currentTime) === 'BEFORE_START') {
    return { kind: 'NotDue' }
  }
  try {
    switch (recurrenceType) {
      case null:
      case undefined:
        return lastCompletionAt ? { kind: 'Completed' } : { kind: 'Due' }
      case 'daily':
        return dailyStatus(recurrenceConfig, lastCompletionAt, currentTime)
      case 'weekly':
        return weeklyStatus(recurrenceConfig, lastCompletionAt, currentTime)
      case 'monthly':
        return monthlyStatus(recurrenceConfig, lastCompletionAt, currentTime)
      case 'interval':
        return intervalStatus(recurrenceConfig, lastCompletionAt, currentTime)
      default:
        return { kind: 'NotDue' }
    }
  } catch {
    return { kind: 'NotDue' }
  }
}

function dailyStatus(configJson: string, last: Date | null, now: Date): OccurrenceStatus {
  const config = parseJsonObject(configJson)
  const today = localDateOf(now)
  if (Array.isArray(config.days)) {
    const allowed = parseDays(config.days)
    if (!allowed.has(dayOfWeek(today))) return nextAllowedDay(today, allowed)
  }
  return completedOnDate(last, today) ? { kind: 'Completed' } : { kind: 'Due' }
}

function weeklyStatus(configJson: string, last: Date | null, now: Date): OccurrenceStatus {
  const config = parseJsonObject(configJson)
  const today = localDateOf(now)
  const targetDay = requireDay(config.day)
  const interval = typeof config.interval === 'number' ? config.interval : 1
  const dueDate = findWeeklyDueDate(today, targetDay, interval)
  if (sameDate(today, dueDate)) {
    return completedOnDate(last, today) ? { kind: 'Completed' } : { kind: 'Due' }
  }
  return { kind: 'Upcoming', nextDueAt: startOfDay(dueDate) }
}

/**
 * Due date for the current or next weekly occurrence. With interval > 1, uses
 * epoch-week counting (weeks since 1970-01-01, a Thursday) — kept exactly as
 * the Android engine does it so both clients pick the same weeks.
 */
function findWeeklyDueDate(today: LocalDate, targetDay: DayIndex, interval: number): LocalDate {
  const monday = plusDays(today, -dayOfWeek(today))
  const thisWeekTarget = plusDays(monday, targetDay)

  if (interval === 1) {
    if (compareDates(today, thisWeekTarget) <= 0) return thisWeekTarget
    return nextStrict(today, targetDay)
  }

  const epochWeek = Math.trunc(toEpochDay(today) / 7)
  const targetWeekOffset = epochWeek % interval
  if (targetWeekOffset === 0 && compareDates(today, thisWeekTarget) <= 0) return thisWeekTarget
  const weeksUntilNext = (interval - targetWeekOffset) % interval
  const nextWeekStart = plusDays(today, weeksUntilNext * 7 - dayOfWeek(today))
  return nextOrSame(nextWeekStart, targetDay)
}

function monthlyStatus(configJson: string, last: Date | null, now: Date): OccurrenceStatus {
  const config = parseJsonObject(configJson)
  const today = localDateOf(now)
  const week = requireInt(config.week)
  const targetDay = requireDay(config.day)
  const dueThisMonth = nthDayOfWeekInMonth(today.year, today.month, week, targetDay)
  const cmp = compareDates(today, dueThisMonth)
  if (cmp === 0) {
    return completedOnDate(last, today) ? { kind: 'Completed' } : { kind: 'Due' }
  }
  if (cmp < 0) return { kind: 'Upcoming', nextDueAt: startOfDay(dueThisMonth) }
  const nextMonth = plusMonths(today, 1)
  return {
    kind: 'Upcoming',
    nextDueAt: startOfDay(nthDayOfWeekInMonth(nextMonth.year, nextMonth.month, week, targetDay)),
  }
}

function nthDayOfWeekInMonth(year: number, month: number, n: number, day: DayIndex): LocalDate {
  const first = nextOrSame({ year, month, day: 1 }, day)
  return plusDays(first, (n - 1) * 7)
}

function intervalStatus(configJson: string, last: Date | null, now: Date): OccurrenceStatus {
  const config = parseJsonObject(configJson)
  const minutes = requireInt(config.minutes)
  const hasWindow = 'active_days' in config || 'active_start' in config
  if (hasWindow && !inActiveWindow(config, now)) return { kind: 'NotDue' }
  if (!last) return { kind: 'Due' }
  const nextDue = new Date(last.getTime() + minutes * 60_000)
  return now.getTime() >= nextDue.getTime() ? { kind: 'Due' } : { kind: 'Upcoming', nextDueAt: nextDue }
}

function inActiveWindow(config: Record<string, any>, now: Date): boolean {
  if (Array.isArray(config.active_days)) {
    if (!parseDays(config.active_days).has(dayOfWeek(localDateOf(now)))) return false
  }
  if ('active_start' in config && 'active_end' in config) {
    const start = parseTaskTime(config.active_start)
    const end = parseTaskTime(config.active_end)
    if (start == null || end == null) throw new Error('bad active window')
    const t = secondsOfDay(now)
    if (t < start || t >= end) return false
  }
  return true
}

/**
 * The next occurrence for a recurring task completed in its current window.
 * Null for one-off tasks.
 */
export function computeNextOccurrence(
  recurrenceType: string | null,
  recurrenceConfig: string,
  lastCompletionAt: Date | null,
  currentTime: Date,
): Date | null {
  if (recurrenceType == null) return null
  const today = localDateOf(currentTime)
  const config = parseJsonObject(recurrenceConfig)
  try {
    switch (recurrenceType) {
      case 'daily': {
        if (Array.isArray(config.days)) {
          const allowed = parseDays(config.days)
          for (let offset = 1; offset <= 7; offset++) {
            const candidate = plusDays(today, offset)
            if (allowed.has(dayOfWeek(candidate))) return startOfDay(candidate)
          }
          return null
        }
        return startOfDay(plusDays(today, 1))
      }
      case 'weekly': {
        const targetDay = requireDay(config.day)
        const interval = typeof config.interval === 'number' ? config.interval : 1
        return startOfDay(findWeeklyDueDate(plusDays(today, 1), targetDay, interval))
      }
      case 'monthly': {
        const week = requireInt(config.week)
        const targetDay = requireDay(config.day)
        const dueThisMonth = nthDayOfWeekInMonth(today.year, today.month, week, targetDay)
        if (compareDates(today, dueThisMonth) < 0) return startOfDay(dueThisMonth)
        const nextMonth = plusMonths(today, 1)
        return startOfDay(nthDayOfWeekInMonth(nextMonth.year, nextMonth.month, week, targetDay))
      }
      case 'interval': {
        if (!lastCompletionAt) return currentTime
        return new Date(lastCompletionAt.getTime() + requireInt(config.minutes) * 60_000)
      }
      default:
        return null
    }
  } catch {
    return null
  }
}

// ── Task status (shared/CONTRACTS.md: Task occurrences and blocking) ──

/** The fields of a task that decide whether it's due, done or overdue. */
export interface TaskStatusInput {
  recurrenceType: string | null
  recurrenceConfig: string
  carryOver: boolean
  availableFrom: string | null
  dueAt: string | null
  createdAt: string
  lastCompletionAt: string | null
}

export interface TaskStatus {
  /** An occurrence is open now: due today, or carried over from an earlier day. */
  scheduled: boolean
  /** The open occurrence is done (with none open: completed today). */
  completed: boolean
  /** The open occurrence started on an earlier day. */
  carriedOver: boolean
  /** Window phase; PAST_DUE when carried over; null with no window or no open occurrence. */
  phase: TimeGatePhase | null
  /** Start of the open occurrence's day, when one is open on a recurring task. */
  occurrenceDate: LocalDate | null
}

/** How far back carry-over looks for the previous occurrence. */
const CARRY_OVER_LOOKBACK_DAYS = 400

export function computeTaskStatus(task: TaskStatusInput, now: Date): TaskStatus {
  const today = localDateOf(now)
  const last = parseInstant(task.lastCompletionAt)
  const phase = computeTaskPhase(task.availableFrom, task.dueAt, now)
  const completedToday = last != null && sameDate(localDateOf(last), today)

  if (task.recurrenceType == null) {
    return { scheduled: true, completed: last != null, carriedOver: false, phase, occurrenceDate: null }
  }
  const scheduledOn = schedulePredicate(task.recurrenceType, task.recurrenceConfig)
  if (scheduledOn == null) {
    // interval: open all the time, done for today once completed today.
    return { scheduled: true, completed: completedToday, carriedOver: false, phase, occurrenceDate: null }
  }

  let open: LocalDate | null = null
  if (scheduledOn(today)) {
    open = today
  } else if (task.carryOver) {
    const created = parseInstant(task.createdAt)
    const earliest = created ? localDateOf(created) : null
    for (let back = 1; back <= CARRY_OVER_LOOKBACK_DAYS; back++) {
      const candidate = plusDays(today, -back)
      if (earliest && compareDates(candidate, earliest) < 0) break
      if (scheduledOn(candidate)) {
        open = candidate
        break
      }
    }
  }

  if (open == null) {
    return { scheduled: false, completed: completedToday, carriedOver: false, phase: null, occurrenceDate: null }
  }
  const carriedOver = !sameDate(open, today)
  return {
    scheduled: true,
    completed: last != null && compareDates(localDateOf(last), open) >= 0,
    carriedOver,
    phase: carriedOver ? 'PAST_DUE' : phase,
    occurrenceDate: open,
  }
}

/**
 * Whether a recurrence lands on a date. Null for types with no calendar dates
 * (interval). A config that can't be parsed lands on every day (fail closed).
 */
function schedulePredicate(type: string, configJson: string): ((d: LocalDate) => boolean) | null {
  const everyDay = () => true
  try {
    const config = parseJsonObject(configJson)
    switch (type) {
      case 'daily': {
        if (!Array.isArray(config.days)) return everyDay
        const allowed = parseDays(config.days)
        return (d) => allowed.has(dayOfWeek(d))
      }
      case 'weekly': {
        const targetDay = requireDay(config.day)
        const interval = typeof config.interval === 'number' ? config.interval : 1
        return (d) => dayOfWeek(d) === targetDay && sameDate(findWeeklyDueDate(d, targetDay, interval), d)
      }
      case 'monthly': {
        const week = requireInt(config.week)
        const targetDay = requireDay(config.day)
        return (d) => sameDate(nthDayOfWeekInMonth(d.year, d.month, week, targetDay), d)
      }
      case 'interval':
        return null
      default:
        return everyDay
    }
  } catch {
    return everyDay
  }
}

// ── Helpers ──────────────────────────────────────────────────────────

function completedOnDate(completion: Date | null, date: LocalDate): boolean {
  return completion != null && sameDate(localDateOf(completion), date)
}

function nextAllowedDay(today: LocalDate, allowed: Set<DayIndex>): OccurrenceStatus {
  for (let offset = 1; offset <= 7; offset++) {
    const candidate = plusDays(today, offset)
    if (allowed.has(dayOfWeek(candidate))) return { kind: 'Upcoming', nextDueAt: startOfDay(candidate) }
  }
  return { kind: 'NotDue' }
}

function nextOrSame(date: LocalDate, day: DayIndex): LocalDate {
  return plusDays(date, (day - dayOfWeek(date) + 7) % 7)
}

function nextStrict(date: LocalDate, day: DayIndex): LocalDate {
  const diff = (day - dayOfWeek(date) + 7) % 7
  return plusDays(date, diff === 0 ? 7 : diff)
}

function parseDays(values: unknown[]): Set<DayIndex> {
  return new Set(values.map((v) => requireDay(v)))
}

function requireDay(value: unknown): DayIndex {
  const day = typeof value === 'string' ? parseDay(value) : undefined
  if (day === undefined) throw new Error(`Unknown day abbreviation: ${value}`)
  return day
}

function requireInt(value: unknown): number {
  const n = typeof value === 'number' ? value : typeof value === 'string' ? Number(value) : NaN
  if (!Number.isFinite(n)) throw new Error(`Expected a number, got ${value}`)
  return Math.trunc(n)
}
