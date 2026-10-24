// Port of domain/shopping/ShoppingNotifyPolicy.kt.

import { dayOfWeek, localDateOf, parseTaskTime, secondsOfDay, startOfDay, DAY_KEYS } from './time'

export interface NotifyWindow {
  /** "mon".."sun"; empty = every day. */
  days: string[]
  /** Seconds of day, or null (from midnight). */
  start: number | null
  /** Seconds of day, or null (until midnight). */
  end: number | null
}

export const NO_WINDOW_RENOTIFY_GAP_MS = 4 * 60 * 60 * 1000

export function parseNotifyWindow(obj: any): NotifyWindow | null {
  if (!obj || typeof obj !== 'object') return null
  return {
    days: Array.isArray(obj.days) ? obj.days.map(String) : [],
    start: parseTaskTime(obj.start ?? ''),
    end: parseTaskTime(obj.end ?? ''),
  }
}

/**
 * Whether a shopping reminder should fire on arrival. Outside the window →
 * never; within a window → at most once per window per day; no window → at
 * most once per 4 hours. The caller checks the list has open items.
 */
export function shouldNotifyShopping(window: NotifyWindow | null, lastNotifiedAt: Date | null, now: Date): boolean {
  if (window) {
    if (window.days.length > 0 && !window.days.includes(DAY_KEYS[dayOfWeek(localDateOf(now))])) return false
    const t = secondsOfDay(now)
    if (window.start != null && t < window.start) return false
    if (window.end != null && t > window.end) return false
  }
  if (!lastNotifiedAt) return true
  const hasTimeWindow = window?.start != null || window?.end != null
  if (hasTimeWindow) {
    const windowStartToday = startOfDay(localDateOf(now)).getTime() + (window?.start ?? 0) * 1000
    return lastNotifiedAt.getTime() < windowStartToday
  }
  return now.getTime() - lastNotifiedAt.getTime() >= NO_WINDOW_RENOTIFY_GAP_MS
}
