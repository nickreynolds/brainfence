/**
 * Minimal database surface shared by the main process (backed directly by the
 * PowerSync database) and the renderer (backed by IPC to the main process).
 * Repositories in ./repos depend only on this, so the same SQL runs in both.
 */

export interface SqlStatement {
  sql: string
  params?: unknown[]
}

export interface Db {
  getAll<T = Record<string, any>>(sql: string, params?: unknown[]): Promise<T[]>
  getOptional<T = Record<string, any>>(sql: string, params?: unknown[]): Promise<T | null>
  execute(sql: string, params?: unknown[]): Promise<void>
  /** Runs all statements in a single write transaction. */
  batch(statements: SqlStatement[]): Promise<void>
  /** The signed-in user's id for local writes, or null if no one has signed in. */
  userId(): Promise<string | null>
}

export async function requireUserId(db: Db): Promise<string> {
  const id = await db.userId()
  if (!id) throw new Error('Not authenticated')
  return id
}

/** SQLite has no boolean type: PowerSync stores BOOLEAN columns as 0/1. */
export const bool = (value: boolean): number => (value ? 1 : 0)

/** Every synced table — used to fan out change notifications. */
export const SYNCED_TABLES = [
  'groups', 'tasks', 'routine_steps', 'shopping_items', 'task_completions', 'step_completions',
  'blocking_rules', 'notes', 'contemplation_decks', 'contemplations', 'contemplation_deliveries',
  'metrics', 'metric_options', 'reports', 'report_metrics', 'report_submissions', 'metric_entries',
] as const

/** This client's platform key in platform-tagged JSON (blocked_apps, companion_apps). */
export const PLATFORM = 'windows'
