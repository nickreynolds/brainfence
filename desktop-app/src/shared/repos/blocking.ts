// Port of data/blocking/BlockingRepository.kt, made platform-aware: rules sync
// across devices, so blocked_apps holds every platform's entries and this
// client must read only its own while preserving the others on write.

import { Db, PLATFORM } from '../db'
import { canonicalJson, nowIso, parseInstant, parseJsonArray, parseJsonObject, parseStringArray, uuid } from '../json'
import type { AppRef, BlockingRule } from '../models'

export const RULE_TABLES = ['blocking_rules']

const RULE_COLUMNS = `id, user_id, name, blocked_apps, blocked_domains, condition_task_ids,
  condition_logic, config_lock_hours, pending_changes, changes_apply_at, is_active`

export async function getActiveRules(db: Db): Promise<BlockingRule[]> {
  return (await db.getAll(`SELECT ${RULE_COLUMNS} FROM blocking_rules WHERE is_active = 1`)).map(mapRule)
}

export async function getAllRules(db: Db): Promise<BlockingRule[]> {
  return (await db.getAll(`SELECT ${RULE_COLUMNS} FROM blocking_rules ORDER BY name`)).map(mapRule)
}

export async function getRule(db: Db, ruleId: string): Promise<BlockingRule | null> {
  const row = await db.getOptional(`SELECT ${RULE_COLUMNS} FROM blocking_rules WHERE id = ?`, [ruleId])
  return row ? mapRule(row) : null
}

export interface RuleDraft {
  name: string
  blockedApps: AppRef[]
  blockedDomains: string[]
  conditionTaskIds: string[]
  conditionLogic: string
  isActive: boolean
}

/** New rules are inserted directly — only weakening an existing rule is time-locked. */
export async function createRule(db: Db, userId: string, draft: RuleDraft): Promise<string> {
  const id = uuid()
  const now = nowIso()
  await db.execute(
    `INSERT INTO blocking_rules
       (id, user_id, name, blocked_apps, blocked_domains, condition_task_ids, condition_logic,
        config_lock_hours, is_active, created_at, updated_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
    [
      id, userId, draft.name, JSON.stringify(draft.blockedApps), JSON.stringify(draft.blockedDomains),
      JSON.stringify(draft.conditionTaskIds), draft.conditionLogic, 24, 1, now, now,
    ],
  )
  return id
}

export async function deleteRule(db: Db, ruleId: string): Promise<void> {
  await db.execute('DELETE FROM blocking_rules WHERE id = ?', [ruleId])
}

/** The JSON object of changes, in the shape the Android client writes too. */
export function draftToChanges(draft: RuleDraft): Record<string, unknown> {
  return {
    name: draft.name,
    blocked_apps: draft.blockedApps,
    blocked_domains: draft.blockedDomains,
    condition_task_ids: draft.conditionTaskIds,
    condition_logic: draft.conditionLogic,
    is_active: draft.isActive ? 1 : 0,
  }
}

export type ScheduleResult = { applied: 'now' } | { applied: 'later'; at: string }

/**
 * Schedule a change with the rule's time-lock. Strictly more restrictive
 * changes apply immediately — the lock only protects against weakening.
 */
export async function scheduleRuleChange(db: Db, ruleId: string, changes: Record<string, unknown>): Promise<ScheduleResult> {
  const current = await getRule(db, ruleId)
  if (current && isMoreRestrictive(current, changes)) {
    await applyPendingToRule(db, ruleId, JSON.stringify(changes))
    return { applied: 'now' }
  }
  const lockHours = current?.configLockHours ?? 24
  const applyAt = new Date(Date.now() + lockHours * 3_600_000).toISOString()
  await db.execute(
    'UPDATE blocking_rules SET pending_changes = ?, changes_apply_at = ?, updated_at = ? WHERE id = ?',
    [JSON.stringify(changes), applyAt, nowIso(), ruleId],
  )
  return { applied: 'later', at: applyAt }
}

/**
 * True if [changes] can only make the rule block more, never less. Shared
 * semantics: shared/CONTRACTS.md; cases: shared/fixtures/rule-restrictiveness.json.
 */
export function isMoreRestrictive(current: BlockingRule, changes: Record<string, any>): boolean {
  if ('blocked_apps' in changes) {
    const next = new Set(asArray(changes.blocked_apps).map(appRefFromJson).filter(isRef).map(appRefKey))
    if (!current.blockedAppRefs.map(appRefKey).every((k) => next.has(k))) return false
  }
  if ('blocked_domains' in changes) {
    const next = new Set(asArray(changes.blocked_domains).map((d) => normalizeDomain(String(d))))
    if (!current.blockedDomains.map(normalizeDomain).filter(Boolean).every((d) => next.has(d))) return false
  }
  const nextLogic = 'condition_logic' in changes ? String(changes.condition_logic) : current.conditionLogic
  if (current.conditionLogic === 'all' && nextLogic === 'any') return false
  if ('condition_task_ids' in changes) {
    const next = new Set(asArray(changes.condition_task_ids).map(String))
    const now = new Set(current.conditionTaskIds)
    const unchanged = next.size === now.size && [...now].every((id) => next.has(id))
    if (!unchanged) {
      // No conditions means the rule always blocks; giving it any loosens it.
      if (now.size === 0) return false
      if (nextLogic === 'any') {
        // Each "any" task is a way to unblock, so only removing them is
        // stricter. An empty set makes the rule always block.
        if (next.size > 0 && ![...next].every((id) => now.has(id))) return false
      } else if (![...now].every((id) => next.has(id))) {
        return false
      }
    }
  }
  if ('is_active' in changes) {
    if (current.isActive && Number(changes.is_active) === 0) return false
  }
  if ('config_lock_hours' in changes) {
    if (Number(changes.config_lock_hours) < current.configLockHours) return false
  }
  return true
}

/** Promote expired pending changes. Returns how many rules were updated. */
export async function applyExpiredPendingChanges(db: Db): Promise<number> {
  // Compared parsed, not as strings: rows replicated from Postgres spell
  // timestamps differently ("2026-04-16 14:00:00+00") from local writes.
  const rows = await db.getAll<{ id: string; pending_changes: string; changes_apply_at: string }>(
    `SELECT id, pending_changes, changes_apply_at FROM blocking_rules
     WHERE pending_changes IS NOT NULL AND changes_apply_at IS NOT NULL`,
  )
  const now = Date.now()
  const due = rows.filter((row) => {
    const at = parseInstant(row.changes_apply_at)
    return at != null && at.getTime() <= now
  })
  for (const row of due) await applyPendingToRule(db, row.id, row.pending_changes)
  return due.length
}

export async function cancelPendingChange(db: Db, ruleId: string): Promise<void> {
  await db.execute(
    'UPDATE blocking_rules SET pending_changes = NULL, changes_apply_at = NULL, updated_at = ? WHERE id = ?',
    [nowIso(), ruleId],
  )
}

async function applyPendingToRule(db: Db, ruleId: string, pendingJson: string): Promise<void> {
  const changes = parseJsonObject(pendingJson)
  const sets: string[] = []
  const params: unknown[] = []
  for (const key of ['name', 'blocked_apps', 'blocked_domains', 'condition_task_ids', 'condition_logic']) {
    if (key in changes) {
      const value = changes[key]
      sets.push(`${key} = ?`)
      params.push(typeof value === 'object' && value !== null ? JSON.stringify(value) : String(value))
    }
  }
  for (const key of ['is_active', 'config_lock_hours']) {
    if (key in changes) {
      sets.push(`${key} = ?`)
      params.push(Math.trunc(Number(changes[key])))
    }
  }
  if (sets.length === 0) return cancelPendingChange(db, ruleId)
  sets.push('pending_changes = NULL', 'changes_apply_at = NULL', 'updated_at = ?')
  params.push(nowIso(), ruleId)
  await db.execute(`UPDATE blocking_rules SET ${sets.join(', ')} WHERE id = ?`, params)
}

export function mapRule(r: Record<string, any>): BlockingRule {
  const refs = parseBlockedApps(r.blocked_apps)
  return {
    id: r.id,
    userId: r.user_id ?? '',
    name: r.name ?? '',
    blockedAppRefs: refs,
    blockedApps: refs.filter((a) => a.platform === PLATFORM).map(appRefKeyId).filter((s) => s.length > 0),
    blockedDomains: parseStringArray(r.blocked_domains).map(normalizeDomain).filter((d) => d.length > 0),
    conditionTaskIds: parseStringArray(r.condition_task_ids),
    conditionLogic: r.condition_logic ?? 'all',
    configLockHours: r.config_lock_hours == null ? 24 : Number(r.config_lock_hours),
    pendingChanges: r.pending_changes ?? null,
    changesApplyAt: r.changes_apply_at ?? null,
    isActive: Number(r.is_active ?? 0) !== 0,
  }
}

/** Reads blocked_apps; bare strings are legacy Android package names. */
export function parseBlockedApps(json: string | null | undefined): AppRef[] {
  return parseJsonArray(json).map(appRefFromJson).filter(isRef)
}

function appRefFromJson(value: unknown): AppRef | null {
  if (typeof value === 'string') return value ? { platform: 'android', package: value } : null
  if (value && typeof value === 'object') return value as AppRef
  return null
}

const isRef = (r: AppRef | null): r is AppRef => r != null

/**
 * The platform-specific identifier of an app reference; for platforms this
 * client doesn't know, the entry's canonical JSON (shared/CONTRACTS.md).
 */
export function appRefKeyId(ref: AppRef): string {
  switch (ref.platform) {
    case 'windows':
      return String(ref.exe ?? '').toLowerCase()
    case 'android':
      return String(ref.package ?? '')
    case 'macos':
      return String(ref.bundle_id ?? '')
    default:
      return canonicalJson(ref)
  }
}

export function appRefKey(ref: AppRef): string {
  return `${ref.platform}:${appRefKeyId(ref)}`
}

export function windowsAppRef(exe: string, name?: string): AppRef {
  const ref: AppRef = { platform: 'windows', exe: exe.toLowerCase() }
  if (name) ref.name = name
  return ref
}

/**
 * "https://www.Twitter.com/home" → "twitter.com"; '' when nothing usable is
 * left. Shared semantics: shared/CONTRACTS.md; cases: shared/fixtures/domains.json.
 */
export function normalizeDomain(input: string): string {
  let d = input.trim().toLowerCase()
  const scheme = d.indexOf('://')
  if (scheme >= 0) d = d.slice(scheme + 3)
  d = d.split(/[/?#]/)[0]
  d = d.slice(d.lastIndexOf('@') + 1)
  const port = d.lastIndexOf(':')
  if (port >= 0) d = d.slice(0, port)
  for (const prefix of ['*.', 'www.']) if (d.startsWith(prefix)) d = d.slice(prefix.length)
  d = d.replace(/\.+$/, '')
  return d.includes('.') ? d : ''
}

function asArray(value: unknown): any[] {
  if (Array.isArray(value)) return value
  if (typeof value === 'string') return parseJsonArray(value)
  return []
}

