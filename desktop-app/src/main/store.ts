// Device-local settings and state in a JSON file under userData — the
// equivalent of Android's SharedPreferences. Nothing here syncs.

import { app } from 'electron'
import { existsSync, mkdirSync, readFileSync, renameSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'

type Json = Record<string, any>

let cache: Json | null = null
let writeTimer: NodeJS.Timeout | null = null

function filePath(): string {
  return join(app.getPath('userData'), 'brainfence-settings.json')
}

function load(): Json {
  if (cache) return cache
  try {
    cache = existsSync(filePath()) ? JSON.parse(readFileSync(filePath(), 'utf8')) : {}
  } catch {
    cache = {}
  }
  return cache!
}

export function getValue<T>(key: string, fallback: T): T {
  const v = load()[key]
  return v === undefined ? fallback : (v as T)
}

export function setValue(key: string, value: unknown): void {
  const data = load()
  if (value === undefined || value === null) delete data[key]
  else data[key] = value
  scheduleWrite()
}

function scheduleWrite(): void {
  if (writeTimer) return
  writeTimer = setTimeout(flushStore, 250)
}

/** Write pending changes now (also called on quit). */
export function flushStore(): void {
  if (writeTimer) clearTimeout(writeTimer)
  writeTimer = null
  if (!cache) return
  const path = filePath()
  mkdirSync(dirname(path), { recursive: true })
  const tmp = `${path}.tmp`
  writeFileSync(tmp, JSON.stringify(cache, null, 2))
  renameSync(tmp, path)
}
