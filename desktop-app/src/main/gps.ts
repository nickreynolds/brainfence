// GPS verification from the Windows location service — the counterpart of
// Android's GpsVerificationManager, driven by periodic fixes rather than OS
// geofences. Leave mode completes when the user is clearly outside the area;
// enter mode once they've been inside for the minimum stay; shopping lists
// remind on arrival when they have open items.
//
// Desktop fixes are often Wi-Fi/IP based and coarse, so the accuracy radius
// is used conservatively: leave only counts when even the near edge of the
// fix's uncertainty is outside the geofence.

import { distanceMeters } from '@shared/blocking'
import type { GpsCheckResult, LocationFix } from '@shared/ipc'
import type { Task } from '@shared/models'
import { computeTaskStatus } from '@shared/recurrence'
import { getOpenItems } from '@shared/repos/shopping'
import { completeTask, getActiveTasks } from '@shared/repos/tasks'
import { shouldNotifyShopping } from '@shared/shopping'
import { type GpsConfig, parseGpsAlternatives, parseGpsConfig } from '@shared/verification'
import { todayKey } from '@shared/time'
import { log } from './debugLog'
import { getLocationStatus, refreshLocation } from './location'
import { notify } from './notifications'
import { mainDb } from './powersync'
import { getValue, setValue } from './store'
import { showMainWindow } from './windows'

type Arrivals = Record<string, { at: string; date: string }>

/**
 * A geofence to watch: a GPS task's own location, or one of a task's GPS
 * completion alternatives (shared/CONTRACTS.md). [key] tracks arrivals.
 */
export interface GpsTarget {
  task: Task
  config: GpsConfig
  key: string
  /** Index in completion_alternatives; null for the task's own GPS check. */
  alternative: number | null
}

export function gpsTasks(tasks: Task[]): GpsTarget[] {
  const out: GpsTarget[] = []
  for (const task of tasks) {
    if (task.verificationType === 'gps') {
      const config = parseGpsConfig(task.verificationConfig)
      if (config) out.push({ task, config, key: task.id, alternative: null })
    }
    for (const alt of parseGpsAlternatives(task.completionAlternatives)) {
      out.push({ task, config: alt.config, key: `${task.id}#alt${alt.index}`, alternative: alt.index })
    }
  }
  return out
}

/** Whether a GPS target still needs watching: notify always; others while due and open. */
export function needsGpsCheck(target: GpsTarget, now: Date): boolean {
  if (target.config.mode === 'notify') return true
  const status = computeTaskStatus(target.task, now)
  return status.scheduled && !status.completed && status.phase !== 'BEFORE_START'
}

/** Apply a location fix to every GPS task. Returns a message per task id. */
export async function processFix(fix: LocationFix, onlyTaskId?: string): Promise<Record<string, string>> {
  const messages: Record<string, string> = {}
  const tasks = await getActiveTasks(mainDb)
  const arrivals = getValue<Arrivals>('gpsArrivals', {})
  const now = new Date()
  const acc = fix.accuracyM ?? 0

  for (const { task, config, key, alternative } of gpsTasks(tasks)) {
    if (onlyTaskId && task.id !== onlyTaskId) continue
    const distance = distanceMeters(fix.latitude, fix.longitude, config.lat, config.lng)
    const inside = distance <= config.radiusM
    const clearlyOutside = distance - acc > config.radiusM

    if (config.mode === 'notify') {
      if (task.taskType === 'shopping' && inside) await maybeShoppingReminder(task, config, now)
      continue
    }
    const status = computeTaskStatus(task, now)
    if (status.completed) {
      messages[task.id] = 'Already completed'
      continue
    }
    if (!status.scheduled) {
      messages[task.id] = 'Not due today'
      continue
    }
    // Before available_from the task isn't completable yet.
    if (status.phase === 'BEFORE_START') {
      messages[task.id] = `Not available until ${task.availableFrom}`
      continue
    }

    if (config.mode === 'leave') {
      if (clearlyOutside) {
        await completeTask(mainDb, task.id, JSON.stringify({
          departed_at: now.toISOString(), lat: fix.latitude, lng: fix.longitude, ...(fix.accuracyM != null ? { accuracy_m: fix.accuracyM } : {}),
          ...alternativeProof(alternative),
        }))
        log('geofence', `Outside geofence for '${task.title}' (${Math.round(distance)}m away), completing`)
        messages[task.id] = `Completed — you're ${Math.round(distance)} m from the location`
      } else {
        messages[task.id] = inside
          ? `Still inside the area (${Math.round(distance)} m from center)`
          : `Can't confirm you've left yet (±${Math.round(acc)} m accuracy)`
      }
      continue
    }

    // Enter mode.
    if (!inside) {
      if (arrivals[key]) delete arrivals[key]
      messages[task.id] = `${Math.round(distance)} m away — not at the location yet`
      continue
    }
    const arrival = arrivals[key]?.date === todayKey(now) ? arrivals[key] : (arrivals[key] = { at: now.toISOString(), date: todayKey(now) })
    const stayedMin = (now.getTime() - new Date(arrival.at).getTime()) / 60_000
    if (stayedMin >= config.minDurationM) {
      await completeTask(mainDb, task.id, JSON.stringify({
        lat: fix.latitude, lng: fix.longitude, ...(fix.accuracyM != null ? { accuracy_m: fix.accuracyM } : {}),
        arrived_at: arrival.at, duration_m: config.minDurationM,
        ...alternativeProof(alternative),
      }))
      delete arrivals[key]
      log('geofence', `At location for '${task.title}', completing`)
      messages[task.id] = 'Completed — you\'re at the location'
    } else {
      messages[task.id] = `At the location — ${Math.ceil(config.minDurationM - stayedMin)} more minute(s) to go`
    }
  }
  setValue('gpsArrivals', arrivals)
  return messages
}

/** Marks a completion made through a completion alternative. */
function alternativeProof(alternative: number | null): Record<string, unknown> {
  return alternative == null ? {} : { method: 'completion_alternative', alternative }
}

async function maybeShoppingReminder(task: Task, config: GpsConfig, now: Date): Promise<void> {
  const last = getValue<Record<string, string>>('shoppingNotifiedAt', {})
  const lastAt = last[task.id] ? new Date(last[task.id]) : null
  if (!shouldNotifyShopping(config.notifyWindow, lastAt, now)) return
  const items = await getOpenItems(mainDb, task.id)
  if (items.length === 0) return
  last[task.id] = now.toISOString()
  setValue('shoppingNotifiedAt', last)
  notify(`${task.title} — ${items.length} item${items.length === 1 ? '' : 's'} to buy`, items.map((i) => i.title).join(', '), () =>
    showMainWindow(`task/${task.id}`),
  )
  log('geofence', `Shopping reminder for '${task.title}' (${items.length} item(s))`)
}

/** "Check now" from a GPS task screen: fresh fix, then evaluate that task. */
export async function checkGpsTask(taskId: string): Promise<GpsCheckResult> {
  const status = await refreshLocation()
  if (status.access === 'denied') return { ok: false, message: 'Location access is off for desktop apps in Windows settings.' }
  const fix = getLocationStatus().lastFix
  if (!fix || status.error) return { ok: false, message: status.error ?? 'No location available' }
  const messages = await processFix(fix, taskId)
  return { ok: true, message: messages[taskId] ?? 'Location checked' }
}
