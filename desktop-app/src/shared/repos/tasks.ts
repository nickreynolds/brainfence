// Port of data/task/TaskRepository.kt and data/completion/CompletionRepository.kt.

import { Db, requireUserId } from '../db'
import { nowIso, uuid } from '../json'
import type { Task } from '../models'

export const TASK_TABLES = ['tasks', 'task_completions']

const ACTIVE_TASKS_SQL = `
  SELECT
    t.id, t.user_id, t.title, t.description, t.task_type, t.status,
    t.recurrence_type, t.recurrence_config, t.verification_type, t.verification_config,
    t.tags, t.group_id, t.sort_order, t.is_blocking_condition, t.blocking_rule_ids,
    t.available_from, t.due_at, t.home_only_blocking, t.blocking_days_of_week,
    t.created_at, t.updated_at,
    CASE WHEN COUNT(tc.id) > 0 THEN 1 ELSE 0 END AS completed_today,
    (SELECT MAX(tc2.completed_at) FROM task_completions tc2 WHERE tc2.task_id = t.id) AS last_completion_at
  FROM tasks t
  LEFT JOIN task_completions tc
    ON tc.task_id = t.id
    AND date(tc.completed_at, 'localtime') = date('now', 'localtime')
  WHERE t.status = 'active'
  GROUP BY t.id
  ORDER BY t.sort_order, t.created_at`

export async function getActiveTasks(db: Db): Promise<Task[]> {
  const rows = await db.getAll(ACTIVE_TASKS_SQL)
  return rows.map(mapTask)
}

export async function getActiveTask(db: Db, taskId: string): Promise<Task | null> {
  return (await getActiveTasks(db)).find((t) => t.id === taskId) ?? null
}

function mapTask(r: Record<string, any>): Task {
  const recurrenceType: string | null = r.recurrence_type ?? null
  const lastCompletionAt: string | null = r.last_completion_at ?? null
  // completed_today counts only completions dated today: right for recurring
  // tasks, wrong for one-offs — a one-off completed yesterday stays done.
  const completedToday = recurrenceType == null ? lastCompletionAt != null : Number(r.completed_today ?? 0) !== 0
  return {
    id: r.id,
    userId: r.user_id ?? '',
    title: r.title ?? '',
    description: r.description ?? null,
    taskType: r.task_type ?? 'simple',
    status: r.status ?? 'active',
    recurrenceType,
    recurrenceConfig: r.recurrence_config ?? '{}',
    verificationType: r.verification_type ?? null,
    verificationConfig: r.verification_config ?? '{}',
    tags: r.tags ?? '[]',
    groupId: r.group_id ?? null,
    sortOrder: Number(r.sort_order ?? 0),
    isBlockingCondition: Number(r.is_blocking_condition ?? 0) !== 0,
    blockingRuleIds: r.blocking_rule_ids ?? '[]',
    availableFrom: r.available_from ?? null,
    dueAt: r.due_at ?? null,
    homeOnlyBlocking: Number(r.home_only_blocking ?? 0) !== 0,
    blockingDaysOfWeek: r.blocking_days_of_week ?? '[]',
    createdAt: r.created_at ?? '',
    updatedAt: r.updated_at ?? '',
    completedToday,
    lastCompletionAt,
  }
}

/**
 * Records a completion. Idempotent per occurrence: at most one completion per
 * one-off task (ever) and per recurring task per local day — the
 * INSERT … WHERE NOT EXISTS makes repeat calls atomic no-ops.
 */
export async function completeTask(db: Db, taskId: string, verificationData = '{}', occurrenceDate = nowIso()): Promise<void> {
  const userId = await requireUserId(db)
  const now = nowIso()
  await db.execute(
    `INSERT INTO task_completions
       (id, task_id, user_id, completed_at, occurrence_date, verification_data, created_at)
     SELECT ?, ?, ?, ?, ?, ?, ?
     WHERE NOT EXISTS (
       SELECT 1 FROM task_completions existing
       WHERE existing.task_id = ?
         AND (
           (SELECT t.recurrence_type FROM tasks t WHERE t.id = ?) IS NULL
           OR date(existing.completed_at, 'localtime') = date('now', 'localtime')
         )
     )`,
    [uuid(), taskId, userId, now, occurrenceDate, verificationData, now, taskId, taskId],
  )
}

/** Replace the proof data on today's completion of [taskId]. */
export async function updateTodaysVerificationData(db: Db, taskId: string, verificationData: string): Promise<void> {
  await db.execute(
    `UPDATE task_completions SET verification_data = ?
     WHERE id = (
       SELECT id FROM task_completions
       WHERE task_id = ? AND date(completed_at, 'localtime') = date('now', 'localtime')
       ORDER BY completed_at DESC LIMIT 1
     )`,
    [verificationData, taskId],
  )
}

/** verification_data JSON of today's completion of [taskId], or null. */
export async function getTodaysVerificationData(db: Db, taskId: string): Promise<string | null> {
  const row = await db.getOptional<{ verification_data: string | null }>(
    `SELECT verification_data FROM task_completions
     WHERE task_id = ? AND date(completed_at, 'localtime') = date('now', 'localtime')
     ORDER BY completed_at DESC LIMIT 1`,
    [taskId],
  )
  return row ? (row.verification_data ?? '{}') : null
}

/** Raw task row for the editor (any status). */
export async function getTaskRow(db: Db, taskId: string): Promise<Record<string, any> | null> {
  return db.getOptional('SELECT * FROM tasks WHERE id = ?', [taskId])
}

export interface CompletionHistoryEntry {
  id: string
  completedAt: string
  verificationData: string
}

/** Most recent completions of a task, newest first. */
export async function getCompletionHistory(db: Db, taskId: string, limit = 30): Promise<CompletionHistoryEntry[]> {
  const rows = await db.getAll(
    `SELECT id, completed_at, verification_data FROM task_completions
     WHERE task_id = ? ORDER BY completed_at DESC LIMIT ?`,
    [taskId, limit],
  )
  return rows.map((r) => ({ id: r.id, completedAt: r.completed_at ?? '', verificationData: r.verification_data ?? '{}' }))
}
