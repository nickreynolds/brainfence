// PowerSync database + Supabase connector, owned by the main process.

import { app } from 'electron'
import {
  AbstractPowerSyncDatabase, column, CrudEntry, PowerSyncBackendConnector, PowerSyncDatabase, Schema,
  SyncStatus, Table, UpdateType,
} from '@powersync/node'
import type { Db, SqlStatement } from '@shared/db'
import { SYNCED_TABLES } from '@shared/db'
import type { SyncStatusInfo } from '@shared/ipc'
import { currentAccessToken, getAuthState, onAuthState, supabase, userIdForLocalWrites } from './auth'
import { POWERSYNC_URL } from './config'

// Mirrors android/.../data/db/AppSchema.kt. JSONB / arrays → text (JSON),
// BOOLEAN → integer (0/1), TIMESTAMPTZ → text. `id` is implicit.
const { text, integer } = column
export const AppSchema = new Schema({
  groups: new Table({ user_id: text, name: text, color: text, icon: text, visibility_schedule: text, sort_order: integer, created_at: text }),
  tasks: new Table({
    user_id: text, title: text, description: text, task_type: text, status: text, recurrence_type: text,
    recurrence_config: text, verification_type: text, verification_config: text, tags: text, group_id: text,
    sort_order: integer, is_blocking_condition: integer, blocking_rule_ids: text, available_from: text, due_at: text,
    home_only_blocking: integer, blocking_days_of_week: text, created_at: text, updated_at: text,
  }),
  routine_steps: new Table({
    user_id: text, task_id: text, title: text, step_order: integer, step_type: text, config: text,
    superset_group: text, description: text, created_at: text,
  }),
  shopping_items: new Table({ user_id: text, task_id: text, title: text, sort_order: integer, completed_at: text, created_at: text }),
  task_completions: new Table({ task_id: text, user_id: text, completed_at: text, occurrence_date: text, verification_data: text, created_at: text }),
  step_completions: new Table({ user_id: text, task_completion_id: text, routine_step_id: text, set_number: integer, data: text, completed_at: text }),
  blocking_rules: new Table({
    user_id: text, name: text, blocked_apps: text, blocked_domains: text, condition_task_ids: text, condition_logic: text,
    config_lock_hours: integer, pending_changes: text, changes_apply_at: text, is_active: integer, created_at: text, updated_at: text,
  }),
  contemplation_decks: new Table({
    user_id: text, name: text, description: text, ordering: text, response_type: text, response_label: text,
    weight: integer, is_enabled: integer, library_key: text, sort_order: integer, created_at: text, updated_at: text,
  }),
  contemplations: new Table({ user_id: text, deck_id: text, title: text, body: text, attribution: text, sort_order: integer, created_at: text, updated_at: text }),
  contemplation_deliveries: new Table({
    user_id: text, deck_id: text, contemplation_id: text, title: text, body: text, attribution: text,
    delivered_at: text, response: text, responded_at: text, created_at: text,
  }),
  metrics: new Table({ user_id: text, name: text, description: text, kind: text, created_at: text, updated_at: text }),
  metric_options: new Table({ user_id: text, metric_id: text, label: text, sort_order: integer, created_at: text, updated_at: text }),
  reports: new Table({ user_id: text, name: text, include_note: integer, library_key: text, created_at: text, updated_at: text }),
  report_metrics: new Table({ user_id: text, report_id: text, metric_id: text, sort_order: integer, created_at: text }),
  report_submissions: new Table({
    user_id: text, report_id: text, task_id: text, routine_step_id: text, report_name: text, note: text, submitted_at: text, created_at: text,
  }),
  metric_entries: new Table({
    user_id: text, submission_id: text, metric_id: text, option_id: text, metric_name: text, metric_kind: text,
    option_label: text, option_index: integer, option_count: integer, position: integer, recorded_at: text, created_at: text,
  }),
  notes: new Table({ user_id: text, title: text, content: text, tags: text, group_id: text, outgoing_links: text, created_at: text, updated_at: text }),
})

/**
 * Columns stored locally as JSON text that are structured in Postgres (JSONB or
 * TEXT[]/UUID[]). They must be parsed before upload, or Postgres stores a JSONB
 * *string* — or rejects the array literal outright, and PowerSync rolls the
 * local write back. Same list as the Android connector.
 */
const JSON_ENCODED_COLUMNS: Record<string, string[]> = {
  tasks: ['recurrence_config', 'verification_config', 'blocking_days_of_week', 'tags', 'blocking_rule_ids'],
  routine_steps: ['config'],
  task_completions: ['verification_data'],
  step_completions: ['data'],
  blocking_rules: ['blocked_apps', 'pending_changes', 'blocked_domains', 'condition_task_ids'],
  groups: ['visibility_schedule'],
  notes: ['tags', 'outgoing_links'],
}

/** Postgres errors that will never succeed on retry: discard so the queue isn't wedged. */
const FATAL_RESPONSE_CODES = [/^22...$/, /^23...$/, /^42501$/]

class SupabaseConnector implements PowerSyncBackendConnector {
  async fetchCredentials() {
    const session = await currentAccessToken()
    if (!session) return null
    return { endpoint: POWERSYNC_URL, token: session.token, expiresAt: session.expiresAt }
  }

  async uploadData(database: AbstractPowerSyncDatabase): Promise<void> {
    const transaction = await database.getNextCrudTransaction()
    if (!transaction) return
    let lastOp: CrudEntry | null = null
    try {
      for (const op of transaction.crud) {
        lastOp = op
        const table = supabase().from(op.table)
        let result: { error: any }
        switch (op.op) {
          case UpdateType.PUT:
            result = await table.upsert({ ...encodeColumns(op.table, op.opData ?? {}), id: op.id })
            break
          case UpdateType.PATCH:
            result = await table.update(encodeColumns(op.table, op.opData ?? {})).eq('id', op.id)
            break
          case UpdateType.DELETE:
            result = await table.delete().eq('id', op.id)
            break
        }
        if (result.error) {
          result.error.message = `Could not ${op.op} ${op.table}/${op.id}: ${result.error.message}`
          throw result.error
        }
      }
      await transaction.complete()
    } catch (e: any) {
      if (typeof e?.code === 'string' && FATAL_RESPONSE_CODES.some((re) => re.test(e.code))) {
        // Same trade-off as PowerSync's Supabase demo: log and drop the bad
        // transaction rather than block every later write behind it.
        console.error('Upload rejected, discarding transaction', lastOp, e)
        await transaction.complete()
      } else {
        throw e
      }
    }
  }
}

function encodeColumns(table: string, data: Record<string, any>): Record<string, any> {
  const cols = JSON_ENCODED_COLUMNS[table]
  if (!cols) return data
  const out = { ...data }
  for (const col of cols) {
    const value = out[col]
    if (typeof value === 'string') {
      try {
        out[col] = JSON.parse(value)
      } catch {
        // Not valid JSON — leave as-is.
      }
    }
  }
  return out
}

let database: PowerSyncDatabase | null = null
const connector = new SupabaseConnector()
const changeListeners = new Set<(tables: string[]) => void>()
const statusListeners = new Set<(s: SyncStatusInfo) => void>()

export function rawDb(): PowerSyncDatabase {
  if (!database) throw new Error('Database not initialised')
  return database
}

export async function initDatabase(): Promise<void> {
  database = new PowerSyncDatabase({
    schema: AppSchema,
    database: {
      dbFilename: 'brainfence.sqlite',
      dbLocation: app.getPath('userData'),
      implementation: { type: 'node:sqlite' },
    },
  })
  await database.init()

  database.onChange(
    {
      onChange: (event) => {
        const tables = [...new Set(event.changedTables.map(normalizeTableName))]
        changeListeners.forEach((l) => l(tables))
      },
    },
    { tables: [...SYNCED_TABLES], throttleMs: 30 },
  )
  database.registerListener({
    statusChanged: (status) => statusListeners.forEach((l) => l(toStatusInfo(status))),
  })

  // Connect while signed in. Local data is only cleared by an explicit sign-out
  // (clearLocalData), never by a transient signed-out state at startup, which
  // would throw away writes that haven't uploaded yet.
  const apply = async () => {
    const auth = getAuthState()
    try {
      if (auth.status === 'signedIn') await database!.connect(connector)
      else if (auth.status === 'signedOut') await database!.disconnect()
    } catch (e) {
      console.error('PowerSync connection change failed', e)
    }
  }
  onAuthState(() => void apply())
  void apply()
}

function normalizeTableName(name: string): string {
  return name.replace(/^ps_data_local__/, '').replace(/^ps_data__/, '')
}

export function onTablesChanged(listener: (tables: string[]) => void): () => void {
  changeListeners.add(listener)
  return () => changeListeners.delete(listener)
}

export function onSyncStatus(listener: (s: SyncStatusInfo) => void): () => void {
  statusListeners.add(listener)
  return () => statusListeners.delete(listener)
}

export function getSyncStatus(): SyncStatusInfo {
  return database ? toStatusInfo(database.currentStatus) : toStatusInfo(null)
}

function toStatusInfo(status: SyncStatus | null): SyncStatusInfo {
  return {
    connected: status?.connected ?? false,
    connecting: status?.connecting ?? false,
    hasSynced: status?.hasSynced ?? false,
    lastSyncedAt: status?.lastSyncedAt?.toISOString() ?? null,
    uploading: status?.dataFlowStatus?.uploading ?? false,
    downloading: status?.dataFlowStatus?.downloading ?? false,
    error: status?.dataFlowStatus?.downloadError?.message ?? status?.dataFlowStatus?.uploadError?.message ?? null,
  }
}

/** node:sqlite rejects booleans and undefined — normalise parameters. */
function sanitize(params?: unknown[]): any[] {
  return (params ?? []).map((p) => (typeof p === 'boolean' ? (p ? 1 : 0) : p === undefined ? null : p))
}

/** The Db used by repositories running in the main process. */
export const mainDb: Db = {
  getAll: (sql, params) => rawDb().getAll(sql, sanitize(params)),
  getOptional: (sql, params) => rawDb().getOptional(sql, sanitize(params)),
  execute: async (sql, params) => {
    await rawDb().execute(sql, sanitize(params))
  },
  batch: (statements: SqlStatement[]) =>
    rawDb().writeTransaction(async (tx) => {
      for (const s of statements) await tx.execute(s.sql, sanitize(s.params))
    }),
  userId: async () => userIdForLocalWrites(),
}

/** After an explicit sign-out: drop local data so the next account starts clean. */
export async function clearLocalData(): Promise<void> {
  await rawDb().disconnectAndClear()
}

export async function closeDatabase(): Promise<void> {
  await database?.close()
}
