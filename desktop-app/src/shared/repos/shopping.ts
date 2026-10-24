// Port of data/shopping/ShoppingRepository.kt and data/journal/JournalRepository.kt.

import { Db, requireUserId } from '../db'
import { nowIso, parseJsonObject, uuid } from '../json'
import type { JournalEntry, ShoppingItem } from '../models'

export const SHOPPING_TABLES = ['shopping_items']

export async function getOpenItems(db: Db, taskId: string): Promise<ShoppingItem[]> {
  const rows = await db.getAll(
    `SELECT id, user_id, task_id, title, sort_order, completed_at, created_at
     FROM shopping_items WHERE task_id = ? AND completed_at IS NULL
     ORDER BY sort_order, created_at`,
    [taskId],
  )
  return rows.map((r) => ({
    id: r.id,
    userId: r.user_id ?? '',
    taskId: r.task_id,
    title: r.title ?? '',
    sortOrder: Number(r.sort_order ?? 0),
    completedAt: r.completed_at ?? null,
    createdAt: r.created_at ?? '',
  }))
}

/** Open-item counts for all shopping tasks, keyed by task id. */
export async function getOpenItemCounts(db: Db): Promise<Record<string, number>> {
  const rows = await db.getAll<{ task_id: string; n: number }>(
    'SELECT task_id, COUNT(*) AS n FROM shopping_items WHERE completed_at IS NULL GROUP BY task_id',
  )
  return Object.fromEntries(rows.map((r) => [r.task_id, Number(r.n)]))
}

export async function addItem(db: Db, taskId: string, title: string): Promise<string> {
  const userId = await requireUserId(db)
  const id = uuid()
  await db.execute(
    `INSERT INTO shopping_items (id, user_id, task_id, title, sort_order, completed_at, created_at)
     VALUES (?, ?, ?, ?, ?, NULL, ?)`,
    [id, userId, taskId, title, 0, nowIso()],
  )
  return id
}

/** Check off an item: it leaves the open list but is kept as history. */
export async function completeItem(db: Db, itemId: string): Promise<void> {
  await db.execute('UPDATE shopping_items SET completed_at = ? WHERE id = ?', [nowIso(), itemId])
}

export async function uncompleteItem(db: Db, itemId: string): Promise<void> {
  await db.execute('UPDATE shopping_items SET completed_at = NULL WHERE id = ?', [itemId])
}

export async function deleteItem(db: Db, itemId: string): Promise<void> {
  await db.execute('DELETE FROM shopping_items WHERE id = ?', [itemId])
}

// ── Journal ──────────────────────────────────────────────────────────

export async function getJournalEntries(db: Db): Promise<JournalEntry[]> {
  const rows = await db.getAll(
    `SELECT tc.id, tc.task_id, t.title, tc.verification_data, tc.completed_at
     FROM task_completions tc JOIN tasks t ON t.id = tc.task_id
     WHERE t.task_type = 'journal'
     ORDER BY tc.completed_at DESC`,
  )
  return rows.map((r) => ({
    id: r.id,
    taskId: r.task_id,
    taskTitle: r.title ?? 'Journal',
    text: String(parseJsonObject(r.verification_data).text ?? ''),
    completedAt: r.completed_at ?? '',
  }))
}
