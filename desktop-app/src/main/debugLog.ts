// Port of data/debug/DebugLogRepository.kt: a local, unsynced log table in the
// PowerSync SQLite file, pruned to 48 hours, shown on the Debug screen.

import type { DebugLogEntry } from '@shared/ipc'
import { uuid } from '@shared/json'
import { rawDb } from './powersync'

let ready: Promise<void> | null = null

function ensureTable(): Promise<void> {
  ready ??= (async () => {
    const db = rawDb()
    await db.execute(`CREATE TABLE IF NOT EXISTS local_debug_logs (
      id TEXT PRIMARY KEY, timestamp TEXT NOT NULL, category TEXT NOT NULL,
      message TEXT NOT NULL, data TEXT, lat REAL, lng REAL, accuracy_m REAL)`)
    await db.execute('DELETE FROM local_debug_logs WHERE timestamp < ?', [new Date(Date.now() - 48 * 3_600_000).toISOString()])
  })()
  return ready
}

export function log(category: string, message: string, data?: string | null): void {
  console.log(`[${category}] ${message}${data ? ` ${data}` : ''}`)
  void (async () => {
    try {
      await ensureTable()
      await rawDb().execute(
        'INSERT INTO local_debug_logs (id, timestamp, category, message, data) VALUES (?, ?, ?, ?, ?)',
        [uuid(), new Date().toISOString(), category, message, data ?? null],
      )
    } catch {
      // Logging must never break the caller.
    }
  })()
}

export async function getLogs(category?: string | null): Promise<DebugLogEntry[]> {
  await ensureTable()
  const rows = category
    ? await rawDb().getAll<any>('SELECT * FROM local_debug_logs WHERE category = ? ORDER BY timestamp DESC LIMIT 500', [category])
    : await rawDb().getAll<any>('SELECT * FROM local_debug_logs ORDER BY timestamp DESC LIMIT 500')
  return rows.map((r) => ({ id: r.id, timestamp: r.timestamp, category: r.category, message: r.message, data: r.data ?? null }))
}

export async function clearLogs(): Promise<void> {
  await ensureTable()
  await rawDb().execute('DELETE FROM local_debug_logs')
}
