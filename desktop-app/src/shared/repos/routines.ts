// Port of data/routine/RoutineRepository.kt.

import { Db, SqlStatement, requireUserId } from '../db'
import { nowIso, uuid } from '../json'
import type { RoutineStep, StepCompletion } from '../models'

export const ROUTINE_TABLES = ['routine_steps']

export interface NewRoutineStep {
  title: string
  stepType: string
  config: string
  supersetGroup: string | null
  description?: string | null
}

export async function getRoutineSteps(db: Db, taskId: string): Promise<RoutineStep[]> {
  const rows = await db.getAll(
    `SELECT id, task_id, title, step_order, step_type, config, superset_group, created_at, description
     FROM routine_steps WHERE task_id = ? ORDER BY step_order`,
    [taskId],
  )
  return rows.map((r) => ({
    id: r.id,
    taskId: r.task_id,
    title: r.title ?? '',
    stepOrder: Number(r.step_order ?? 0),
    stepType: r.step_type ?? 'checkbox',
    config: r.config ?? '{}',
    supersetGroup: r.superset_group ?? null,
    createdAt: r.created_at ?? '',
    description: r.description ?? null,
  }))
}

/** Step completions from the most recent completion of [taskId], for pre-filling. */
export async function getLastStepCompletions(db: Db, taskId: string): Promise<StepCompletion[]> {
  const last = await db.getOptional<{ id: string }>(
    'SELECT id FROM task_completions WHERE task_id = ? ORDER BY completed_at DESC LIMIT 1',
    [taskId],
  )
  if (!last) return []
  const rows = await db.getAll(
    `SELECT id, task_completion_id, routine_step_id, set_number, data, completed_at
     FROM step_completions WHERE task_completion_id = ? ORDER BY routine_step_id, set_number`,
    [last.id],
  )
  return rows.map((r) => ({
    id: r.id,
    taskCompletionId: r.task_completion_id,
    routineStepId: r.routine_step_id,
    setNumber: Number(r.set_number ?? 1),
    data: r.data ?? '{}',
    completedAt: r.completed_at ?? '',
  }))
}

/**
 * Complete a routine: one task_completion plus one step_completion per set per
 * step. [stepData] maps routine step id → per-set JSON strings.
 */
export async function completeRoutine(db: Db, taskId: string, stepData: Record<string, string[]>): Promise<string> {
  const userId = await requireUserId(db)
  const now = nowIso()
  const completionId = uuid()
  const statements: SqlStatement[] = [
    {
      sql: `INSERT INTO task_completions
              (id, task_id, user_id, completed_at, occurrence_date, verification_data, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)`,
      params: [completionId, taskId, userId, now, now, JSON.stringify({ method: 'routine', steps: Object.keys(stepData).length }), now],
    },
  ]
  for (const [stepId, sets] of Object.entries(stepData)) {
    sets.forEach((data, index) => {
      statements.push({
        sql: `INSERT INTO step_completions
                (id, user_id, task_completion_id, routine_step_id, set_number, data, completed_at)
              VALUES (?, ?, ?, ?, ?, ?, ?)`,
        params: [uuid(), userId, completionId, stepId, index + 1, data, now],
      })
    })
  }
  await db.batch(statements)
  return completionId
}

export function insertStepStatement(userId: string, taskId: string, step: NewRoutineStep, stepOrder: number, id = uuid()): SqlStatement {
  return {
    sql: `INSERT INTO routine_steps
            (id, user_id, task_id, title, step_order, step_type, config, superset_group, created_at, description)
          VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
    params: [id, userId, taskId, step.title, stepOrder, step.stepType, step.config, step.supersetGroup, nowIso(), step.description ?? null],
  }
}

export async function insertSingleStep(db: Db, taskId: string, step: NewRoutineStep, stepOrder: number): Promise<void> {
  const userId = await requireUserId(db)
  const stmt = insertStepStatement(userId, taskId, step, stepOrder)
  await db.execute(stmt.sql, stmt.params)
}

export async function deleteStep(db: Db, stepId: string): Promise<void> {
  await db.execute('DELETE FROM routine_steps WHERE id = ?', [stepId])
}
