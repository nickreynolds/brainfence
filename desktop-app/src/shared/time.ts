// Local-calendar helpers standing in for java.time's LocalDate / LocalTime.
// Everything here is in the system time zone, like the Android client's
// ZoneId.systemDefault().

/** A calendar date with no time zone. Month is 1-12. */
export interface LocalDate {
  year: number
  month: number
  day: number
}

/** Mon=0 … Sun=6, matching java.time.DayOfWeek.ordinal. */
export type DayIndex = 0 | 1 | 2 | 3 | 4 | 5 | 6

const DAY_ABBREVIATIONS: Record<string, DayIndex> = {
  mon: 0, tue: 1, wed: 2, thu: 3, fri: 4, sat: 5, sun: 6,
}

export const DAY_KEYS = ['mon', 'tue', 'wed', 'thu', 'fri', 'sat', 'sun'] as const

export function parseDay(abbrev: string): DayIndex | undefined {
  return DAY_ABBREVIATIONS[abbrev.toLowerCase()]
}

export function localDateOf(instant: Date): LocalDate {
  return { year: instant.getFullYear(), month: instant.getMonth() + 1, day: instant.getDate() }
}

export function toEpochDay(date: LocalDate): number {
  return Math.round(Date.UTC(date.year, date.month - 1, date.day) / 86_400_000)
}

export function fromEpochDay(epochDay: number): LocalDate {
  const d = new Date(epochDay * 86_400_000)
  return { year: d.getUTCFullYear(), month: d.getUTCMonth() + 1, day: d.getUTCDate() }
}

export function plusDays(date: LocalDate, days: number): LocalDate {
  return fromEpochDay(toEpochDay(date) + days)
}

export function plusMonths(date: LocalDate, months: number): LocalDate {
  const total = date.year * 12 + (date.month - 1) + months
  const year = Math.floor(total / 12)
  const month = (total % 12) + 1
  const lastDay = new Date(Date.UTC(year, month, 0)).getUTCDate()
  return { year, month, day: Math.min(date.day, lastDay) }
}

export function dayOfWeek(date: LocalDate): DayIndex {
  // 1970-01-01 was a Thursday (index 3).
  return ((((toEpochDay(date) + 3) % 7) + 7) % 7) as DayIndex
}

export function compareDates(a: LocalDate, b: LocalDate): number {
  return toEpochDay(a) - toEpochDay(b)
}

export function sameDate(a: LocalDate, b: LocalDate): boolean {
  return compareDates(a, b) === 0
}

/** Midnight at the start of [date] in the system zone. */
export function startOfDay(date: LocalDate): Date {
  return new Date(date.year, date.month - 1, date.day, 0, 0, 0, 0)
}

export function atTime(date: LocalDate, secondsOfDay: number): Date {
  const d = startOfDay(date)
  d.setSeconds(secondsOfDay)
  return d
}

/** Seconds since local midnight, with millisecond precision. */
export function secondsOfDay(instant: Date): number {
  return instant.getHours() * 3600 + instant.getMinutes() * 60 + instant.getSeconds() + instant.getMilliseconds() / 1000
}

export function isoDate(date: LocalDate): string {
  return `${date.year}-${String(date.month).padStart(2, '0')}-${String(date.day).padStart(2, '0')}`
}

export function todayKey(now: Date = new Date()): string {
  return isoDate(localDateOf(now))
}

/**
 * Parses "H:mm" or "HH:mm" (Android's parseTaskTime) into seconds of day.
 * Null for blank or malformed input.
 */
export function parseTaskTime(value: string | null | undefined): number | null {
  if (value == null) return null
  const m = /^\s*(\d{1,2}):(\d{2})\s*$/.exec(value)
  if (!m) return null
  const h = Number(m[1])
  const min = Number(m[2])
  if (h > 23 || min > 59) return null
  return h * 3600 + min * 60
}

export function formatHHmm(secondsOfDayValue: number): string {
  const h = Math.floor(secondsOfDayValue / 3600) % 24
  const m = Math.floor((secondsOfDayValue % 3600) / 60)
  return `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}`
}
