// Supabase auth in the main process. The session is persisted encrypted with
// Electron safeStorage (DPAPI on Windows) — the counterpart of Android's
// EncryptedSessionManager — and kept refreshing for the whole process
// lifetime, since Brainfence mostly works in the background.

import { app, safeStorage } from 'electron'
import { existsSync, readFileSync, writeFileSync, unlinkSync } from 'node:fs'
import { join } from 'node:path'
import { createClient, type SupabaseClient } from '@supabase/supabase-js'
import type { AuthState } from '@shared/ipc'
import { AUTH_CALLBACK_URL, CONFIRMED_NOTICE, linkErrorNotice, parseAuthCallback } from '@shared/authCallback'
import { SUPABASE_PUBLISHABLE_KEY, SUPABASE_URL } from './config'
import { getValue, setValue } from './store'

const sessionFile = () => join(app.getPath('userData'), 'session.bin')

function readEncrypted(): Record<string, string> {
  try {
    if (!existsSync(sessionFile())) return {}
    const buf = readFileSync(sessionFile())
    const text = safeStorage.isEncryptionAvailable() ? safeStorage.decryptString(buf) : buf.toString('utf8')
    return JSON.parse(text)
  } catch {
    // Unreadable (e.g. profile moved to another machine): start signed out.
    return {}
  }
}

function writeEncrypted(data: Record<string, string>): void {
  if (Object.keys(data).length === 0) {
    if (existsSync(sessionFile())) unlinkSync(sessionFile())
    return
  }
  const text = JSON.stringify(data)
  writeFileSync(sessionFile(), safeStorage.isEncryptionAvailable() ? safeStorage.encryptString(text) : Buffer.from(text, 'utf8'))
}

const storage = {
  getItem: (key: string) => readEncrypted()[key] ?? null,
  setItem: (key: string, value: string) => writeEncrypted({ ...readEncrypted(), [key]: value }),
  removeItem: (key: string) => {
    const data = readEncrypted()
    delete data[key]
    writeEncrypted(data)
  },
}

let client: SupabaseClient | null = null
let state: AuthState = { status: 'loading' }
const listeners = new Set<(s: AuthState) => void>()

export function supabase(): SupabaseClient {
  if (!client) {
    if (!SUPABASE_URL || !SUPABASE_PUBLISHABLE_KEY) {
      throw new Error('Supabase is not configured: set SUPABASE_URL and SUPABASE_PUBLISHABLE_KEY in the repo-root .env and rebuild')
    }
    client = createClient(SUPABASE_URL, SUPABASE_PUBLISHABLE_KEY, {
      auth: { storage, persistSession: true, autoRefreshToken: true, detectSessionInUrl: false, flowType: 'pkce' },
    })
  }
  return client
}

export function initAuth(): void {
  supabase().auth.onAuthStateChange((event, session) => {
    if (session?.user) {
      setValue('lastUserId', session.user.id)
      setState({ status: 'signedIn', userId: session.user.id, email: session.user.email ?? null })
    } else if (event === 'SIGNED_OUT' || event === 'INITIAL_SESSION') {
      if (event === 'SIGNED_OUT') setValue('lastUserId', null)
      setState({ status: 'signedOut' })
    }
  })
}

function setState(next: AuthState): void {
  const prev = state
  state = next
  // Token refreshes re-emit SIGNED_IN for the same user; only notify on real changes.
  const same = prev.status === next.status && (prev.status !== 'signedIn' || (next.status === 'signedIn' && prev.userId === next.userId))
  if (!same) listeners.forEach((l) => l(next))
}

export const getAuthState = (): AuthState => state

export function onAuthState(listener: (s: AuthState) => void): () => void {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

/**
 * The user id for local writes. Falls back to the last signed-in user when a
 * token refresh is pending offline: a local PowerSync write only needs the id,
 * and uploads once the session is valid again.
 */
export function userIdForLocalWrites(): string | null {
  if (state.status === 'signedIn') return state.userId
  return getValue<string | null>('lastUserId', null)
}

export async function signIn(email: string, password: string): Promise<void> {
  const { error } = await supabase().auth.signInWithPassword({ email: email.trim(), password })
  if (error) throw new Error(error.message)
}

export async function signUp(email: string, password: string): Promise<{ needsConfirmation: boolean }> {
  const { data, error } = await supabase().auth.signUp({
    email: email.trim(),
    password,
    options: { emailRedirectTo: AUTH_CALLBACK_URL },
  })
  if (error) throw new Error(error.message)
  return { needsConfirmation: !data.session }
}

export async function signOut(): Promise<void> {
  const { error } = await supabase().auth.signOut()
  // Offline sign-out still clears the local session.
  if (error) await supabase().auth.signOut({ scope: 'local' })
}

/**
 * Deletes the signed-in account on the server, then signs out locally (the
 * server session went with the user). shared/CONTRACTS.md, Account deletion.
 */
export async function deleteAccount(): Promise<void> {
  const { error } = await supabase().rpc('delete_account')
  if (error) throw new Error(error.message)
  await supabase().auth.signOut({ scope: 'local' })
}

export async function currentAccessToken(): Promise<{ token: string; expiresAt?: Date } | null> {
  const { data } = await supabase().auth.getSession()
  const session = data.session
  if (!session) return null
  return { token: session.access_token, expiresAt: session.expires_at ? new Date(session.expires_at * 1000) : undefined }
}

// ── Email links (shared/CONTRACTS.md, Email links) ──────────────────────────

let pendingNotice: string | null = null
const noticeListeners = new Set<(notice: string) => void>()

function setNotice(notice: string): void {
  pendingNotice = notice
  noticeListeners.forEach((l) => l(notice))
}

/** The sign-in screen's notice from the last email link, cleared once read. */
export function takeNotice(): string | null {
  const n = pendingNotice
  pendingNotice = null
  return n
}

export function onNotice(listener: (notice: string) => void): () => void {
  noticeListeners.add(listener)
  return () => noticeListeners.delete(listener)
}

/** Handles a brainfence://auth-callback link. Returns false for other URLs. */
export async function handleAuthCallback(url: string): Promise<boolean> {
  const link = parseAuthCallback(url)
  if (!link) return false
  if (link.kind === 'error') {
    setNotice(linkErrorNotice(link.message))
    return true
  }
  // Supabase confirmed the email before redirecting; signing in is a bonus. It
  // fails when the link is opened on a different device than the sign-up.
  const { error } =
    link.kind === 'code' ? await supabase().auth.exchangeCodeForSession(link.code)
    : link.kind === 'tokens' ? await supabase().auth.setSession({ access_token: link.access_token, refresh_token: link.refresh_token })
    : { error: new Error('nothing to sign in with') }
  if (error) setNotice(CONFIRMED_NOTICE)
  return true
}
