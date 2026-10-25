// The synced setup-guide state (one `onboarding` row per user, id = user_id)
// and the writes that finish the guide or a device's setup. Shared semantics:
// shared/CONTRACTS.md (Onboarding).

import { Db, PLATFORM, SqlStatement, requireUserId } from '../db'
import { nowIso, parseJsonArray, parseJsonObject, uuid } from '../json'
import type { AppRef } from '../models'
import { CONTEMPLATION_LIBRARY } from '../contemplationLibrary'
import {
  DeferredStep, OnboardingPlan, OnboardingPlatform, OnboardingRow, PlannedTask, StarterKey, canLocate,
} from '../onboarding'
import { appRefKey, getRule, scheduleRuleChange } from './blocking'
import { libraryDeckStatements } from './contemplations'
import { insertStepStatement } from './routines'

export const ONBOARDING_TABLES = ['onboarding']

const PLATFORMS: OnboardingPlatform[] = ['android', 'windows', 'macos']
const setupColumn = (p: OnboardingPlatform) => `${p}_setup_at`

export async function getOnboarding(db: Db): Promise<OnboardingRow | null> {
  const row = await db.getOptional('SELECT * FROM onboarding LIMIT 1')
  return row ? mapOnboarding(row) : null
}

export function mapOnboarding(r: Record<string, any>): OnboardingRow {
  return {
    status: r.status ?? '',
    deferredSteps: parseJsonArray(r.deferred_steps).filter((d) => d && typeof d.key === 'string') as DeferredStep[],
    blockingRuleId: r.blocking_rule_id ?? null,
    meditationTaskId: r.meditation_task_id ?? null,
    setupAt: { android: r.android_setup_at ?? null, windows: r.windows_setup_at ?? null, macos: r.macos_setup_at ?? null },
  }
}

/** Insert the user's row, or update the given columns of it. */
async function upsertStatement(db: Db, userId: string, columns: Record<string, unknown>): Promise<SqlStatement> {
  const now = nowIso()
  const exists = await db.getOptional('SELECT id FROM onboarding WHERE id = ?', [userId])
  if (exists) {
    const keys = Object.keys(columns)
    return {
      sql: `UPDATE onboarding SET ${keys.map((k) => `${k} = ?`).join(', ')}, updated_at = ? WHERE id = ?`,
      params: [...keys.map((k) => columns[k]), now, userId],
    }
  }
  const row: Record<string, unknown> = { deferred_steps: '[]', ...columns, user_id: userId, created_at: now, updated_at: now }
  const keys = Object.keys(row)
  return {
    sql: `INSERT INTO onboarding (id, ${keys.join(', ')}) VALUES (?, ${keys.map(() => '?').join(', ')})`,
    params: [userId, ...keys.map((k) => row[k])],
  }
}

/** Skipped, or an existing account that never saw the guide. */
export async function dismissOnboarding(db: Db): Promise<void> {
  const userId = await requireUserId(db)
  const stmt = await upsertStatement(db, userId, { status: 'dismissed' })
  await db.execute(stmt.sql, stmt.params)
}

function taskStatements(userId: string, task: PlannedTask, taskId: string): SqlStatement[] {
  const now = nowIso()
  return [
    {
      sql: `INSERT INTO tasks
              (id, user_id, title, description, task_type, status, recurrence_type, recurrence_config,
               verification_type, verification_config, tags, sort_order, is_blocking_condition, blocking_rule_ids,
               available_from, due_at, home_only_blocking, blocking_days_of_week, created_at, updated_at)
            VALUES (?, ?, ?, NULL, ?, 'active', ?, ?, ?, ?, '[]', ?, ?, '[]', ?, ?, 0, '[]', ?, ?)`,
      params: [
        taskId, userId, task.title, task.task_type, task.recurrence_type, JSON.stringify(task.recurrence_config),
        task.verification_type, JSON.stringify(task.verification_config), task.sort_order, task.is_blocking_condition ? 1 : 0,
        task.available_from, task.due_at, now, now,
      ],
    },
    ...task.steps.map((step, i) =>
      insertStepStatement(userId, taskId, { title: step.title, stepType: step.stepType, config: JSON.stringify(step.config), supersetGroup: null }, i),
    ),
  ]
}

async function remembrancesStatements(db: Db, userId: string): Promise<SqlStatement[]> {
  const deck = CONTEMPLATION_LIBRARY.find((d) => d.key === 'five_remembrances')!
  const existing = await db.getOptional('SELECT id FROM contemplation_decks WHERE library_key = ?', [deck.key])
  return existing ? [] : libraryDeckStatements(userId, deck)
}

export interface GuideResult {
  taskIds: Partial<Record<StarterKey, string>>
  ruleId: string | null
}

/** Writes everything the guide planned, and marks the guide completed. */
export async function applyGuide(db: Db, plan: OnboardingPlan, platform: OnboardingPlatform = PLATFORM): Promise<GuideResult> {
  const userId = await requireUserId(db)
  const now = nowIso()
  const statements: SqlStatement[] = []
  const taskIds: GuideResult['taskIds'] = {}
  for (const task of plan.tasks) {
    const id = uuid()
    taskIds[task.key] = id
    statements.push(...taskStatements(userId, task, id))
  }
  if (plan.add_remembrances) statements.push(...(await remembrancesStatements(db, userId)))

  let ruleId: string | null = null
  if (plan.rule) {
    ruleId = uuid()
    statements.push({
      sql: `INSERT INTO blocking_rules
              (id, user_id, name, blocked_apps, blocked_domains, condition_task_ids, condition_logic,
               config_lock_hours, is_active, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, 24, 1, ?, ?)`,
      params: [
        ruleId, userId, plan.rule.name, JSON.stringify(plan.rule.blocked_apps), JSON.stringify(plan.rule.blocked_domains),
        JSON.stringify(plan.rule.condition_keys.map((k) => taskIds[k])), plan.rule.condition_logic, now, now,
      ],
    })
  }

  const previous = await getOnboarding(db)
  const meditationTaskId = taskIds.meditation ?? null
  const madeShared = ruleId != null || meditationTaskId != null
  const columns: Record<string, unknown> = {
    status: 'completed',
    completed_at: now,
    deferred_steps: JSON.stringify(plan.deferred_steps),
    blocking_rule_id: ruleId ?? previous?.blockingRuleId ?? null,
    meditation_task_id: meditationTaskId ?? previous?.meditationTaskId ?? null,
  }
  // Something new for other devices to add their own apps to: ask them again.
  for (const p of PLATFORMS) {
    if (p === platform) columns[setupColumn(p)] = now
    else if (madeShared) columns[setupColumn(p)] = null
  }
  statements.push(await upsertStatement(db, userId, columns))
  await db.batch(statements)
  return { taskIds, ruleId }
}

export interface DeviceSetup {
  /** This platform's apps to add to the guide's rule. */
  blockedApps: AppRef[]
  /** This platform's companion apps for the guide's meditation task (replaces this platform's entries), or null to leave them. */
  companionApps: AppRef[] | null
  /** The deferred starters the user kept, planned with the locations now known. Those still missing one stay deferred. */
  deferredPlan: OnboardingPlan | null
}

/** Finishes this device's setup, then records it on the synced row. */
export async function finishDeviceSetup(db: Db, setup: DeviceSetup, platform: OnboardingPlatform = PLATFORM): Promise<void> {
  const userId = await requireUserId(db)
  const row = await getOnboarding(db)
  const rule = row?.blockingRuleId ? await getRule(db, row.blockingRuleId) : null

  const newTaskIds: string[] = []
  if (setup.deferredPlan && setup.deferredPlan.tasks.length > 0) {
    const statements: SqlStatement[] = []
    for (const task of setup.deferredPlan.tasks) {
      const id = uuid()
      if (task.is_blocking_condition) newTaskIds.push(id)
      statements.push(...taskStatements(userId, task, id))
    }
    await db.batch(statements)
  }

  if (rule) {
    const changes: Record<string, unknown> = {}
    const have = new Set(rule.blockedAppRefs.map(appRefKey))
    const added = setup.blockedApps.filter((a) => !have.has(appRefKey(a)))
    if (added.length > 0) changes.blocked_apps = [...rule.blockedAppRefs, ...added]
    if (newTaskIds.length > 0) changes.condition_task_ids = [...rule.conditionTaskIds, ...newTaskIds]
    // Adding apps, or conditions under "all", is stricter: applied at once.
    if (Object.keys(changes).length > 0) await scheduleRuleChange(db, rule.id, changes)
  }

  if (setup.companionApps && row?.meditationTaskId) {
    const task = await db.getOptional<{ verification_config: string | null }>('SELECT verification_config FROM tasks WHERE id = ?', [row.meditationTaskId])
    if (task) {
      const config = parseJsonObject(task.verification_config)
      const others = parseJsonArray(JSON.stringify(config.companion_apps ?? [])).filter((a) => typeof a === 'string' || a?.platform !== platform)
      config.companion_apps = [...others, ...setup.companionApps]
      await db.execute('UPDATE tasks SET verification_config = ?, updated_at = ? WHERE id = ?', [JSON.stringify(config), nowIso(), row.meditationTaskId])
    }
  }

  const columns: Record<string, unknown> = { [setupColumn(platform)]: nowIso() }
  if (canLocate(platform)) columns.deferred_steps = JSON.stringify(setup.deferredPlan?.deferred_steps ?? [])
  if (!row) columns.status = 'completed'
  const stmt = await upsertStatement(db, userId, columns)
  await db.execute(stmt.sql, stmt.params)
}
