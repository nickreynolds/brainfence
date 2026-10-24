/**
 * Tolerant JSON helpers for the JSON-as-text columns PowerSync stores locally.
 *
 * PowerSync can double-encode JSONB (a JSON string whose content is JSON), and
 * Postgres arrays occasionally arrive as `{a,b}` literals. Every reader goes
 * through these so a malformed value degrades to an empty default instead of
 * throwing in the middle of blocking evaluation.
 */

export function parseJsonObject(json: string | null | undefined): Record<string, any> {
  const value = parseLoose(json)
  return value && typeof value === 'object' && !Array.isArray(value) ? value : {}
}

export function parseJsonArray(json: string | null | undefined): any[] {
  if (json == null) return []
  const trimmed = json.trim()
  if (trimmed === '' || trimmed === 'null') return []
  const value = parseLoose(trimmed)
  if (Array.isArray(value)) return value
  // Postgres array literal: {a,b,"c d"}
  if (trimmed.startsWith('{') && trimmed.endsWith('}') && value === undefined) {
    const inner = trimmed.slice(1, -1).trim()
    if (inner === '') return []
    return inner.split(',').map((s) => s.trim().replace(/^"(.*)"$/, '$1'))
  }
  return []
}

export function parseStringArray(json: string | null | undefined): string[] {
  return parseJsonArray(json)
    .filter((v) => v != null)
    .map((v) => String(v))
    .filter((v) => v.length > 0)
}

/** Parses JSON, unwrapping one level of double-encoding. Undefined if unparseable. */
function parseLoose(json: string | null | undefined): any {
  if (json == null) return undefined
  try {
    let value = JSON.parse(json)
    if (typeof value === 'string') {
      try {
        value = JSON.parse(value)
      } catch {
        return undefined
      }
    }
    return value
  } catch {
    return undefined
  }
}

/**
 * Parses timestamps as PowerSync stores them: ISO-8601 ("2026-10-23T12:00:00Z")
 * or Postgres text ("2026-10-23 12:00:00.123+00"). Null if unparseable.
 */
export function parseInstant(value: string | null | undefined): Date | null {
  if (!value) return null
  let s = value.trim().replace(' ', 'T')
  // "+00" / "-05" offsets → "+00:00"
  s = s.replace(/([+-]\d{2})$/, '$1:00')
  const d = new Date(s)
  return isNaN(d.getTime()) ? null : d
}

export function nowIso(): string {
  return new Date().toISOString()
}

export function uuid(): string {
  return crypto.randomUUID()
}
