// Email links that Supabase Auth redirects to brainfence://auth-callback.
// Shared semantics: shared/CONTRACTS.md (Email links); cases:
// shared/fixtures/auth-callback.json, which Android and macOS run too.

export const AUTH_CALLBACK_URL = 'brainfence://auth-callback'

export type AuthCallback =
  | { kind: 'code'; code: string }
  | { kind: 'tokens'; access_token: string; refresh_token: string }
  | { kind: 'error'; message: string }
  | { kind: 'none' }

export const CONFIRMED_NOTICE = 'Your email is confirmed. Sign in to continue.'
export const linkErrorNotice = (message: string) => `That link didn't work: ${message}. Sign up again with the same email to get a new one.`

/** Classifies [url], or null when it isn't an auth callback. */
export function parseAuthCallback(url: string): AuthCallback | null {
  let u: URL
  try {
    u = new URL(url)
  } catch {
    return null
  }
  if (u.protocol !== 'brainfence:' || u.hostname !== 'auth-callback') return null
  const query = u.searchParams
  const fragment = new URLSearchParams(u.hash.replace(/^#/, ''))
  const param = (key: string) => query.get(key) || fragment.get(key) || null

  const error = param('error_description') ?? param('error')
  if (error) return { kind: 'error', message: error }
  const code = query.get('code')
  if (code) return { kind: 'code', code }
  const access = fragment.get('access_token')
  const refresh = fragment.get('refresh_token')
  if (access && refresh) return { kind: 'tokens', access_token: access, refresh_token: refresh }
  return { kind: 'none' }
}
