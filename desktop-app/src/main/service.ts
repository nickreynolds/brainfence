// The background service — counterpart of Android's BrainfenceService:
// keeps tasks/rules current, re-evaluates blocking reactively and every
// minute, promotes expired time-locked rule changes, enforces app and website
// blocks, fires task notifications and drives location checks.

import { EMPTY_BLOCKING_STATE, evaluateBlocking } from '@shared/blocking'
import type { EnforcementInfo } from '@shared/ipc'
import type { BlockingRule, Task } from '@shared/models'
import { appRefKeyId, applyExpiredPendingChanges, getActiveRules } from '@shared/repos/blocking'
import { getActiveTasks } from '@shared/repos/tasks'
import { parseMeditationConfig } from '@shared/verification'
import { PLATFORM } from '@shared/db'
import { foregroundExe, onForegroundChange, setBlockedApps, startEnforcer } from './blocking/enforcer'
import { applyBlockedDomains, isHostsWritable, websiteStatus } from './blocking/hosts'
import { log } from './debugLog'
import { gpsTasks, processFix } from './gps'
import { getHome, homePresence, onHomeChange, refreshLocation } from './location'
import { evaluateTaskNotifications } from './notifications'
import { mainDb, onTablesChanged } from './powersync'
import {
  cancelMeditation, hasMeditationTimer, meditationCompanionApps, onForegroundForCompanions, startCompanionTracking,
} from './timers'

const EVAL_INTERVAL_MS = 60_000
const WATCHED = new Set(['tasks', 'task_completions', 'blocking_rules'])

let tasks: Task[] = []
let rules: BlockingRule[] = []
let info: EnforcementInfo = {
  state: EMPTY_BLOCKING_STATE, homePresence: 'UNCONFIGURED', foregroundExe: null,
  website: { writable: false, activeDomains: [], error: null }, evaluatedAt: new Date(0).toISOString(),
}
const listeners = new Set<(i: EnforcementInfo) => void>()
let refreshTimer: NodeJS.Timeout | null = null
let locationTimer: NodeJS.Timeout | null = null

export const getEnforcementInfo = (): EnforcementInfo => ({ ...info, foregroundExe: foregroundExe() })
export const getCachedTasks = (): Task[] => tasks

export function onEnforcementInfo(listener: (i: EnforcementInfo) => void): () => void {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

async function refreshData(): Promise<void> {
  try {
    ;[tasks, rules] = await Promise.all([getActiveTasks(mainDb), getActiveRules(mainDb)])
  } catch (e) {
    console.warn('Data refresh failed; using cached data', e)
  }
}

export function evaluateNow(): void {
  const state = evaluateBlocking(rules, tasks, new Date(), homePresence())
  const names = new Map<string, string>()
  for (const rule of rules) {
    for (const ref of rule.blockedAppRefs) {
      if (ref.platform === PLATFORM && ref.name) names.set(appRefKeyId(ref), String(ref.name))
    }
  }
  setBlockedApps(state.blockedApps, names)
  if (isHostsWritable()) applyBlockedDomains(state.blockedDomains)

  const prevCount = info.state.blockedApps.length + info.state.blockedDomains.length
  info = {
    state,
    homePresence: homePresence(),
    foregroundExe: foregroundExe(),
    website: websiteStatus(),
    evaluatedAt: new Date().toISOString(),
  }
  const count = state.blockedApps.length + state.blockedDomains.length
  if (count !== prevCount) log('blocking', `Evaluated: ${state.blockedApps.length} app(s), ${state.blockedDomains.length} domain(s) blocked`)
  listeners.forEach((l) => l(info))
}

/** Full cycle: fresh data, then evaluate. */
export async function reevaluate(): Promise<void> {
  await refreshData()
  evaluateNow()
  autoStartCompanionTracking()
}

/** Start companion tracking for meditation tasks with this platform's companion apps. */
function autoStartCompanionTracking(): void {
  for (const task of tasks) {
    if (task.verificationType !== 'meditation' || task.completedToday) continue
    const existing = hasMeditationTimer(task.id)
    // The user chose the in-app timer — don't replace their session.
    if (existing?.method === 'in_app_timer') continue
    const config = parseMeditationConfig(task.verificationConfig)
    if (!config || config.companionApps.length === 0) continue
    if (existing) {
      const persisted = meditationCompanionApps(task.id) ?? []
      if (persisted.length === config.companionApps.length && persisted.every((a) => config.companionApps.includes(a))) continue
      log('companion', `Restarting tracking for '${task.title}' — companion apps changed`)
      cancelMeditation(task.id)
    }
    startCompanionTracking(task.id, task.title, config)
  }
}

async function periodic(): Promise<void> {
  try {
    const applied = await applyExpiredPendingChanges(mainDb)
    if (applied > 0) log('config_lock', `Applied pending config changes to ${applied} rule(s)`)
  } catch (e) {
    console.error('Applying pending config changes failed', e)
  }
  await reevaluate()
  const enforced = new Set(rules.flatMap((r) => r.conditionTaskIds))
  evaluateTaskNotifications(tasks, enforced)
}

/** Location only matters with a home set or GPS tasks; poll more often for GPS. */
async function locationCycle(): Promise<void> {
  const active = gpsTasks(tasks).filter(({ task, config }) => config.mode === 'notify' || !task.completedToday)
  const needed = getHome() != null || active.length > 0
  if (needed) {
    const status = await refreshLocation()
    if (status.lastFix && status.access === 'allowed') {
      try {
        await processFix(status.lastFix)
      } catch (e) {
        console.warn('GPS processing failed', e)
      }
    }
    evaluateNow()
  }
  const delay = active.length > 0 ? 2 * 60_000 : 10 * 60_000
  locationTimer = setTimeout(() => void locationCycle(), delay)
}

export function scheduleLocationCheckSoon(): void {
  if (locationTimer) clearTimeout(locationTimer)
  locationTimer = setTimeout(() => void locationCycle(), 1000)
}

export async function startService(): Promise<void> {
  startEnforcer()
  onForegroundChange((exe) => onForegroundForCompanions(exe))
  onTablesChanged((changed) => {
    if (!changed.some((t) => WATCHED.has(t))) return
    // Coalesce bursts (e.g. a sync download) into one refresh.
    if (refreshTimer) clearTimeout(refreshTimer)
    refreshTimer = setTimeout(() => void reevaluate(), 150)
  })
  onHomeChange(() => {
    evaluateNow()
    scheduleLocationCheckSoon()
  })
  await periodic()
  setInterval(() => void periodic(), EVAL_INTERVAL_MS)
  locationTimer = setTimeout(() => void locationCycle(), 5_000)
  log('service', 'Brainfence service started')
}
