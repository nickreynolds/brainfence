import { useCallback, useEffect, useRef, useState } from 'react'
import type { Db } from '@shared/db'
import type { AuthState, EnforcementInfo, LocationStatus, SyncStatusInfo, TimerStates } from '@shared/ipc'

/** The renderer's Db: every call goes to the main process's PowerSync database. */
export const db: Db = {
  getAll: (sql, params) => window.api.db.getAll(sql, params),
  getOptional: (sql, params) => window.api.db.getOptional(sql, params),
  execute: (sql, params) => window.api.db.execute(sql, params),
  batch: (statements) => window.api.db.batch(statements),
  userId: () => window.api.db.userId(),
}

/**
 * Runs [load] now and again whenever any of [tables] changes (locally or via
 * sync) — the counterpart of the Android repositories' onChange-backed flows.
 */
export function useQuery<T>(load: () => Promise<T>, tables: string[], deps: unknown[], initial: T): { data: T; loading: boolean; reload: () => void } {
  const [data, setData] = useState<T>(initial)
  const [loading, setLoading] = useState(true)
  const loadRef = useRef(load)
  loadRef.current = load
  const seq = useRef(0)

  const reload = useCallback(() => {
    const mine = ++seq.current
    loadRef.current()
      .then((value) => {
        if (mine === seq.current) {
          setData(value)
          setLoading(false)
        }
      })
      .catch((e) => {
        console.error('Query failed', e)
        if (mine === seq.current) setLoading(false)
      })
  }, [])

  useEffect(() => {
    reload()
    const watched = new Set(tables)
    return window.api.db.onChange((changed) => {
      if (changed.some((t) => watched.has(t))) reload()
    })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps)

  return { data, loading, reload }
}

/** Re-renders every [intervalMs] so time-based UI (phases, countdowns) stays current. */
export function useNow(intervalMs = 30_000): Date {
  const [now, setNow] = useState(() => new Date())
  useEffect(() => {
    const id = setInterval(() => setNow(new Date()), intervalMs)
    return () => clearInterval(id)
  }, [intervalMs])
  return now
}

function useApiValue<T>(get: () => Promise<T>, subscribe: (cb: (v: T) => void) => () => void, initial: T): T {
  const [value, setValue] = useState<T>(initial)
  useEffect(() => {
    let alive = true
    void get().then((v) => alive && setValue(v))
    const off = subscribe((v) => setValue(v))
    return () => {
      alive = false
      off()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])
  return value
}

export const useAuth = () => useApiValue<AuthState>(() => window.api.auth.getState(), (cb) => window.api.auth.onState(cb), { status: 'loading' })

export const useSyncStatus = () =>
  useApiValue<SyncStatusInfo | null>(() => window.api.sync.getStatus(), (cb) => window.api.sync.onStatus(cb), null)

export const useEnforcement = () =>
  useApiValue<EnforcementInfo | null>(() => window.api.blocking.getInfo(), (cb) => window.api.blocking.onInfo(cb), null)

export const useTimers = () =>
  useApiValue<TimerStates>(() => window.api.timers.getStates(), (cb) => window.api.timers.onStates(cb), { duration: {}, meditation: {} })

export const useLocationStatus = () =>
  useApiValue<LocationStatus | null>(() => window.api.location.getStatus(), (cb) => window.api.location.onStatus(cb), null)

/** Wraps an async action with a busy flag and error capture. */
export function useAction(): { busy: boolean; error: string | null; run: (fn: () => Promise<unknown>) => Promise<boolean>; setError: (e: string | null) => void } {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const run = useCallback(async (fn: () => Promise<unknown>) => {
    setBusy(true)
    setError(null)
    try {
      await fn()
      return true
    } catch (e: any) {
      setError(cleanError(e))
      return false
    } finally {
      setBusy(false)
    }
  }, [])
  return { busy, error, run, setError }
}

/** IPC errors arrive as "Error invoking remote method 'x': Error: message". */
export function cleanError(e: any): string {
  const msg = String(e?.message ?? e)
  return msg.replace(/^Error invoking remote method '[^']+': (Error: )?/, '')
}
