// The setup guide's rules: where a device goes, and the rows the guide writes.
// Shared semantics: shared/CONTRACTS.md (Onboarding); cases:
// shared/fixtures/onboarding.json, which Android and macOS run too.

import { normalizeDomain } from './repos/blocking'
import type { AppRef } from './models'

export type StarterKey = 'morning_routine' | 'morning_walk' | 'meditation' | 'journaling' | 'groceries' | 'five_remembrances'

export interface StarterStep {
  title: string
  stepType: string
  config: Record<string, unknown>
}

export interface StarterDef {
  key: StarterKey
  title: string
  /** One line for the guide: what the task is and how it's completed. */
  summary: string
  /** Has a time window the user can change. */
  hasWindow: boolean
  availableFrom: string | null
  dueAt: string | null
  /** A condition of the guide's blocking rule. */
  blocks: boolean
  /** Which location the starter needs; deferred until it's known. */
  needs: 'home' | 'store' | null
  sortOrder: number
}

export const STARTERS: StarterDef[] = [
  { key: 'morning_routine', title: 'Morning Routine', summary: 'A checklist you run through: make bed, take pills, then a 30-second reverse plank.', hasWindow: true, availableFrom: '06:00', dueAt: '09:00', blocks: true, needs: null, sortOrder: 0 },
  { key: 'morning_walk', title: 'Morning Walk', summary: 'Completes on its own when your phone leaves home.', hasWindow: true, availableFrom: '06:00', dueAt: '11:00', blocks: true, needs: 'home', sortOrder: 1 },
  { key: 'meditation', title: 'Meditation', summary: 'Ten minutes with the built-in timer or a meditation app you choose.', hasWindow: true, availableFrom: '06:00', dueAt: '19:00', blocks: true, needs: null, sortOrder: 2 },
  { key: 'journaling', title: 'Journal', summary: 'Write a short entry each evening. Past entries are kept in Journal.', hasWindow: true, availableFrom: '18:00', dueAt: '23:00', blocks: false, needs: null, sortOrder: 3 },
  { key: 'groceries', title: 'Groceries', summary: "A running shopping list that reminds you when you're near the store.", hasWindow: false, availableFrom: null, dueAt: null, blocks: false, needs: 'store', sortOrder: 4 },
  { key: 'five_remembrances', title: 'The Five Remembrances', summary: 'Short reflections delivered a few times a day. Tap "I remember" to acknowledge one.', hasWindow: false, availableFrom: null, dueAt: null, blocks: false, needs: null, sortOrder: 5 },
]

export const starterDef = (key: StarterKey): StarterDef => STARTERS.find((s) => s.key === key)!

export const MORNING_ROUTINE_STEPS: StarterStep[] = [
  { title: 'Make Bed', stepType: 'checkbox', config: {} },
  { title: 'Take Pills', stepType: 'checkbox', config: {} },
  { title: 'Reverse Plank', stepType: 'timed', config: { duration_seconds: 30 } },
]

export const ONBOARDING_RULE_NAME = 'Distractions'
export const MEDITATION_SECONDS = 600
export const MEDITATION_SHORT_SIT_SECONDS = 120
export const GROCERIES_RADIUS_M = 100
const EVERY_DAY = ['mon', 'tue', 'wed', 'thu', 'fri', 'sat', 'sun']

// ── Routing ─────────────────────────────────────────────────────────────────

export type OnboardingPlatform = 'android' | 'windows' | 'macos'
export type OnboardingRoute = 'wait' | 'guide' | 'device_setup' | 'auto_dismiss' | 'none'

export interface DeferredStep {
  key: StarterKey
  available_from?: string
  due_at?: string
}

export interface OnboardingRow {
  status: string
  deferredSteps: DeferredStep[]
  blockingRuleId: string | null
  meditationTaskId: string | null
  setupAt: Record<OnboardingPlatform, string | null>
}

/** Android and Windows have a home location; the Mac doesn't. */
export const canLocate = (platform: OnboardingPlatform): boolean => platform !== 'macos'

export function onboardingRoute(args: {
  platform: OnboardingPlatform
  hasSynced: boolean
  onboarding: OnboardingRow | null
  taskCount: number
}): OnboardingRoute {
  const { platform, hasSynced, onboarding, taskCount } = args
  if (!hasSynced) return 'wait'
  if (!onboarding) return taskCount > 0 ? 'auto_dismiss' : 'guide'
  if (onboarding.status !== 'completed') return 'none'
  if (canLocate(platform) && onboarding.deferredSteps.length > 0) return 'device_setup'
  if (!onboarding.setupAt[platform]) return 'device_setup'
  return 'none'
}

// ── Plan ────────────────────────────────────────────────────────────────────

export interface StarterChoice {
  available_from?: string
  due_at?: string
}

export interface PlaceChoice {
  latitude: number
  longitude: number
  radius_meters?: number
}

export interface OnboardingChoices {
  /** Present = turned on; values override the default window. */
  starters: Partial<Record<StarterKey, StarterChoice>>
  home: PlaceChoice | null
  store: PlaceChoice | null
  /** This platform's companion apps for the meditation task. */
  companion_apps: AppRef[]
  /** This platform's apps for the rule. */
  blocked_apps: AppRef[]
  blocked_domains: string[]
}

export interface PlannedTask {
  key: StarterKey
  title: string
  task_type: string
  verification_type: string | null
  verification_config: Record<string, unknown>
  recurrence_type: string | null
  recurrence_config: Record<string, unknown>
  is_blocking_condition: boolean
  available_from: string | null
  due_at: string | null
  sort_order: number
  steps: StarterStep[]
}

export interface PlannedRule {
  name: string
  blocked_apps: AppRef[]
  blocked_domains: string[]
  condition_keys: StarterKey[]
  condition_logic: 'all'
}

export interface OnboardingPlan {
  tasks: PlannedTask[]
  deferred_steps: DeferredStep[]
  add_remembrances: boolean
  rule: PlannedRule | null
}

export function buildOnboardingPlan(choices: OnboardingChoices): OnboardingPlan {
  const tasks: PlannedTask[] = []
  const deferred: DeferredStep[] = []
  for (const def of STARTERS) {
    const choice = choices.starters[def.key]
    if (!choice || def.key === 'five_remembrances') continue
    const availableFrom = def.hasWindow ? choice.available_from ?? def.availableFrom : null
    const dueAt = def.hasWindow ? choice.due_at ?? def.dueAt : null
    const place = def.needs === 'home' ? choices.home : def.needs === 'store' ? choices.store : null
    if (def.needs && !place) {
      deferred.push(def.hasWindow ? { key: def.key, available_from: availableFrom!, due_at: dueAt! } : { key: def.key })
      continue
    }
    tasks.push({ ...starterTask(def, choices, place), available_from: availableFrom, due_at: dueAt })
  }

  const blockedDomains = [...new Set(choices.blocked_domains.map(normalizeDomain).filter(Boolean))]
  const conditionKeys = tasks.filter((t) => t.is_blocking_condition).map((t) => t.key)
  const blocksSomething = choices.blocked_apps.length > 0 || blockedDomains.length > 0
  return {
    tasks,
    deferred_steps: deferred,
    add_remembrances: 'five_remembrances' in choices.starters,
    rule: blocksSomething && conditionKeys.length > 0
      ? { name: ONBOARDING_RULE_NAME, blocked_apps: choices.blocked_apps, blocked_domains: blockedDomains, condition_keys: conditionKeys, condition_logic: 'all' }
      : null,
  }
}

function starterTask(def: StarterDef, choices: OnboardingChoices, place: PlaceChoice | null): Omit<PlannedTask, 'available_from' | 'due_at'> {
  const base = {
    key: def.key, title: def.title, task_type: 'simple', verification_type: null as string | null,
    verification_config: {} as Record<string, unknown>, recurrence_type: 'daily' as string | null,
    recurrence_config: {}, is_blocking_condition: def.blocks, sort_order: def.sortOrder, steps: [] as StarterStep[],
  }
  switch (def.key) {
    case 'morning_routine':
      return { ...base, task_type: 'routine', steps: MORNING_ROUTINE_STEPS }
    case 'morning_walk':
      return {
        ...base, verification_type: 'gps',
        verification_config: { latitude: place!.latitude, longitude: place!.longitude, radius_meters: place!.radius_meters ?? 100, mode: 'leave', min_duration_m: 0 },
      }
    case 'meditation':
      return {
        ...base, verification_type: 'meditation',
        verification_config: { duration_seconds: MEDITATION_SECONDS, short_sit_seconds: MEDITATION_SHORT_SIT_SECONDS, companion_apps: choices.companion_apps },
      }
    case 'journaling':
      return { ...base, task_type: 'journal', verification_type: 'journal' }
    case 'groceries':
      return {
        ...base, task_type: 'shopping', verification_type: 'gps', recurrence_type: null,
        verification_config: {
          latitude: place!.latitude, longitude: place!.longitude, radius_meters: GROCERIES_RADIUS_M, mode: 'notify',
          notify_window: { days: EVERY_DAY, start: '08:00', end: '21:00' },
        },
      }
    default:
      return base
  }
}

/** Choices that re-create deferred starters, once their locations are known. */
export function deferredChoices(deferred: DeferredStep[], home: PlaceChoice | null, store: PlaceChoice | null): OnboardingChoices {
  const starters: OnboardingChoices['starters'] = {}
  for (const d of deferred) {
    if (!STARTERS.some((s) => s.key === d.key)) continue
    starters[d.key] = { available_from: d.available_from, due_at: d.due_at }
  }
  return { starters, home, store, companion_apps: [], blocked_apps: [], blocked_domains: [] }
}

/** Domains offered on the blocking step. */
export const SUGGESTED_DOMAINS = ['youtube.com', 'reddit.com', 'x.com', 'instagram.com', 'facebook.com', 'tiktok.com']
