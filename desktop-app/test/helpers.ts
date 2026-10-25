// Tests run in America/New_York, like the Android unit tests. Set before any
// Date is created; Node re-reads TZ when it changes.
process.env.TZ = 'America/New_York'

import { DatabaseSync } from 'node:sqlite'
import type { Db } from '../src/shared/db'
import type { BlockingRule, Task } from '../src/shared/models'

/** Local wall-clock time in the test zone. */
export function at(date: string, hour = 12, minute = 0): Date {
  const [y, m, d] = date.split('-').map(Number)
  return new Date(y, m - 1, d, hour, minute, 0, 0)
}

export function task(overrides: Partial<Task> = {}): Task {
  return {
    id: 'task-1', userId: 'user-1', title: 'Morning routine', description: null, taskType: 'habit', status: 'active',
    recurrenceType: 'daily', recurrenceConfig: '{}', verificationType: 'manual', verificationConfig: '{}', tags: '[]',
    groupId: null, sortOrder: 0, isBlockingCondition: true, blockingRuleIds: '[]', availableFrom: '07:00', dueAt: '10:00',
    homeOnlyBlocking: false, blockingDaysOfWeek: '[]', createdAt: '2026-04-01T00:00:00Z', updatedAt: '2026-04-01T00:00:00Z',
    completedToday: false, lastCompletionAt: null, ...overrides,
  }
}

export function rule(overrides: Partial<BlockingRule> = {}): BlockingRule {
  return {
    id: 'rule-1', userId: 'user-1', name: 'Block social media',
    blockedAppRefs: [{ platform: 'windows', exe: 'discord.exe' }], blockedApps: ['discord.exe'], blockedDomains: [],
    conditionTaskIds: ['task-1'], conditionLogic: 'all', configLockHours: 24, pendingChanges: null, changesApplyAt: null,
    isActive: true, ...overrides,
  }
}

const TABLES: Record<string, string[]> = {
  tasks: ['user_id', 'title', 'description', 'task_type', 'status', 'recurrence_type', 'recurrence_config', 'verification_type',
    'verification_config', 'tags', 'group_id', 'sort_order', 'is_blocking_condition', 'blocking_rule_ids', 'available_from',
    'due_at', 'home_only_blocking', 'blocking_days_of_week', 'created_at', 'updated_at'],
  routine_steps: ['user_id', 'task_id', 'title', 'step_order', 'step_type', 'config', 'superset_group', 'description', 'created_at'],
  task_completions: ['task_id', 'user_id', 'completed_at', 'occurrence_date', 'verification_data', 'created_at'],
  step_completions: ['user_id', 'task_completion_id', 'routine_step_id', 'set_number', 'data', 'completed_at'],
  blocking_rules: ['user_id', 'name', 'blocked_apps', 'blocked_domains', 'condition_task_ids', 'condition_logic',
    'config_lock_hours', 'pending_changes', 'changes_apply_at', 'is_active', 'created_at', 'updated_at'],
  shopping_items: ['user_id', 'task_id', 'title', 'sort_order', 'completed_at', 'created_at'],
  metrics: ['user_id', 'name', 'description', 'kind', 'created_at', 'updated_at'],
  metric_options: ['user_id', 'metric_id', 'label', 'sort_order', 'created_at', 'updated_at'],
  reports: ['user_id', 'name', 'include_note', 'library_key', 'created_at', 'updated_at'],
  report_metrics: ['user_id', 'report_id', 'metric_id', 'sort_order', 'created_at'],
  report_submissions: ['user_id', 'report_id', 'task_id', 'routine_step_id', 'report_name', 'note', 'submitted_at', 'created_at'],
  metric_entries: ['user_id', 'submission_id', 'metric_id', 'option_id', 'metric_name', 'metric_kind', 'option_label',
    'option_index', 'option_count', 'position', 'recorded_at', 'created_at'],
  contemplation_decks: ['user_id', 'name', 'description', 'ordering', 'response_type', 'response_label', 'weight', 'is_enabled',
    'library_key', 'sort_order', 'created_at', 'updated_at'],
  contemplations: ['user_id', 'deck_id', 'title', 'body', 'attribution', 'sort_order', 'created_at', 'updated_at'],
  contemplation_deliveries: ['user_id', 'deck_id', 'contemplation_id', 'title', 'body', 'attribution', 'delivered_at', 'response',
    'responded_at', 'created_at'],
  onboarding: ['user_id', 'status', 'deferred_steps', 'blocking_rule_id', 'meditation_task_id', 'android_setup_at',
    'windows_setup_at', 'macos_setup_at', 'completed_at', 'created_at', 'updated_at'],
}

/** An in-memory SQLite Db with the synced tables' columns (no PowerSync views). */
export function memoryDb(userId: string | null = 'user-1'): Db & { raw: DatabaseSync } {
  const raw = new DatabaseSync(':memory:')
  for (const [name, cols] of Object.entries(TABLES)) {
    raw.exec(`CREATE TABLE ${name} (id TEXT PRIMARY KEY, ${cols.join(', ')})`)
  }
  const run = (sql: string, params: unknown[] = []) => raw.prepare(sql).run(...(params as any[]))
  return {
    raw,
    getAll: async (sql, params = []) => raw.prepare(sql).all(...(params as any[])) as any[],
    getOptional: async (sql, params = []) => (raw.prepare(sql).get(...(params as any[])) as any) ?? null,
    execute: async (sql, params) => {
      run(sql, params)
    },
    batch: async (statements) => {
      raw.exec('BEGIN')
      try {
        for (const s of statements) run(s.sql, s.params)
        raw.exec('COMMIT')
      } catch (e) {
        raw.exec('ROLLBACK')
        throw e
      }
    },
    userId: async () => userId,
  }
}
