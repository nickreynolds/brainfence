// Port of domain/blocking/BlockingEvaluator.kt — pure, no side effects.

import { parseStringArray } from './json'
import type { BlockingRule, Task } from './models'
import { computeTaskPhase } from './recurrence'
import { dayOfWeek, localDateOf, parseDay } from './time'

export interface BlockingState {
  /** This platform's blocked app identifiers (lowercased exe names on Windows). */
  blockedApps: string[]
  blockedDomains: string[]
  /** Rule ids that are blocking right now. */
  blockingRuleIds: string[]
  /** Blocked app → ids of the rules blocking it. */
  rulesByApp: Record<string, string[]>
  /** Blocked app → task ids that are unmet (causing the block). */
  unmetTaskIdsByApp: Record<string, string[]>
  /** Every unmet condition task across blocking rules (drives domain blocks too). */
  unmetTaskIds: string[]
}

export const EMPTY_BLOCKING_STATE: BlockingState = {
  blockedApps: [],
  blockedDomains: [],
  blockingRuleIds: [],
  rulesByApp: {},
  unmetTaskIdsByApp: {},
  unmetTaskIds: [],
}

/**
 * Whether the user is within their configured home radius. UNCONFIGURED and
 * UNKNOWN both keep home-only tasks blocking (strict by default — blocking only
 * lifts when we positively know the user is away).
 */
export type HomePresence = 'AT_HOME' | 'AWAY' | 'UNKNOWN' | 'UNCONFIGURED'

/**
 * A rule blocks when it is active and its condition tasks are not met. A task
 * only triggers blocking once it is past its due_at (or immediately, when it
 * has no due_at) and still incomplete.
 */
export function evaluateBlocking(
  rules: BlockingRule[],
  tasks: Task[],
  currentTime: Date = new Date(),
  homePresence: HomePresence = 'UNCONFIGURED',
): BlockingState {
  const blockedApps = new Set<string>()
  const blockedDomains = new Set<string>()
  const blockingRuleIds: string[] = []
  const rulesByApp: Record<string, string[]> = {}
  const unmetTaskIdsByApp: Record<string, Set<string>> = {}
  const allUnmet = new Set<string>()
  const taskById = new Map(tasks.map((t) => [t.id, t]))

  for (const rule of rules) {
    if (!rule.isActive) continue
    const unmet = conditionUnmetTaskIds(rule, taskById, currentTime, homePresence)
    if (isConditionMet(rule, unmet)) continue

    blockingRuleIds.push(rule.id)
    unmet.forEach((id) => allUnmet.add(id))
    rule.blockedDomains.forEach((d) => blockedDomains.add(d))
    for (const app of rule.blockedApps) {
      blockedApps.add(app)
      ;(rulesByApp[app] ??= []).push(rule.id)
      const set = (unmetTaskIdsByApp[app] ??= new Set())
      unmet.forEach((id) => set.add(id))
    }
  }

  return {
    blockedApps: [...blockedApps],
    blockedDomains: [...blockedDomains],
    blockingRuleIds,
    rulesByApp,
    unmetTaskIdsByApp: Object.fromEntries(Object.entries(unmetTaskIdsByApp).map(([k, v]) => [k, [...v]])),
    unmetTaskIds: [...allUnmet],
  }
}

function isConditionMet(rule: BlockingRule, unmet: Set<string>): boolean {
  if (rule.conditionTaskIds.length === 0) return false
  if (rule.conditionLogic === 'any') return unmet.size < rule.conditionTaskIds.length
  return unmet.size === 0
}

function conditionUnmetTaskIds(
  rule: BlockingRule,
  taskById: Map<string, Task>,
  currentTime: Date,
  homePresence: HomePresence,
): Set<string> {
  const out = new Set<string>()
  for (const taskId of rule.conditionTaskIds) {
    const task = taskById.get(taskId)
    // Fail closed: an unresolvable condition task (deleted, archived, not yet
    // synced) counts as unmet so blocking never silently lifts.
    if (!task) {
      out.add(taskId)
      continue
    }
    if (task.completedToday) continue
    if (task.homeOnlyBlocking && homePresence === 'AWAY') continue
    const days = parseBlockingDays(task.blockingDaysOfWeek)
    if (days.size > 0 && !days.has(dayOfWeek(localDateOf(currentTime)))) continue
    const phase = computeTaskPhase(task.availableFrom, task.dueAt, currentTime)
    if (phase == null || phase === 'PAST_DUE') out.add(taskId)
  }
  return out
}

function parseBlockingDays(json: string): Set<number> {
  const out = new Set<number>()
  for (const d of parseStringArray(json)) {
    const idx = parseDay(d)
    if (idx !== undefined) out.add(idx)
  }
  return out
}

/** Great-circle distance in meters (haversine). */
export function distanceMeters(lat1: number, lng1: number, lat2: number, lng2: number): number {
  const R = 6_371_008.8
  const toRad = (deg: number) => (deg * Math.PI) / 180
  const dLat = toRad(lat2 - lat1)
  const dLng = toRad(lng2 - lng1)
  const a = Math.sin(dLat / 2) ** 2 + Math.cos(toRad(lat1)) * Math.cos(toRad(lat2)) * Math.sin(dLng / 2) ** 2
  return 2 * R * Math.asin(Math.min(1, Math.sqrt(a)))
}
