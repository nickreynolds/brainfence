import { parseInstant } from '@shared/json'

/** 2:05 → "02:05"; 1:02:05 for an hour or more (Android's formatMeditationTime). */
export function formatClock(totalSeconds: number): string {
  const s = Math.max(0, Math.floor(totalSeconds))
  const h = Math.floor(s / 3600)
  const m = Math.floor((s % 3600) / 60)
  const sec = s % 60
  const pad = (n: number) => String(n).padStart(2, '0')
  return h > 0 ? `${h}:${pad(m)}:${pad(sec)}` : `${pad(m)}:${pad(sec)}`
}

/** "2 min" for whole minutes, otherwise m:ss. */
export function formatMinutes(totalSeconds: number): string {
  return totalSeconds % 60 === 0 ? `${totalSeconds / 60} min` : formatClock(totalSeconds)
}

/** "Later today", "Tomorrow", a weekday, or "Apr 30". */
export function formatNextDue(next: Date, now = new Date()): string {
  const day = (d: Date) => new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime()
  const days = Math.round((day(next) - day(now)) / 86_400_000)
  if (days <= 0) return 'Later today'
  if (days === 1) return 'Tomorrow'
  if (days <= 6) return next.toLocaleDateString(undefined, { weekday: 'long' })
  return next.toLocaleDateString(undefined, { month: 'short', day: 'numeric' })
}

export function formatDateTime(iso: string | null | undefined): string {
  const d = parseInstant(iso)
  if (!d) return ''
  return d.toLocaleString(undefined, { weekday: 'short', month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' })
}

export function formatTime(iso: string | null | undefined): string {
  const d = parseInstant(iso)
  return d ? d.toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' }) : ''
}

export function isToday(iso: string | null | undefined, now = new Date()): boolean {
  const d = parseInstant(iso)
  return !!d && d.toDateString() === now.toDateString()
}

export function plural(n: number, word: string, pluralWord = `${word}s`): string {
  return `${n} ${n === 1 ? word : pluralWord}`
}

export function capitalize(s: string): string {
  return s.charAt(0).toUpperCase() + s.slice(1)
}

/** Relative "in 23h 47m" for time-locked changes. */
export function formatCountdown(iso: string | null | undefined, now = new Date()): string {
  const d = parseInstant(iso)
  if (!d) return ''
  const mins = Math.max(0, Math.round((d.getTime() - now.getTime()) / 60_000))
  if (mins < 1) return 'any moment now'
  const h = Math.floor(mins / 60)
  const m = mins % 60
  return h > 0 ? `in ${h}h ${m}m` : `in ${m}m`
}
