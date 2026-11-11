// Parsers for tasks' verification_config, accepting every spelling the
// Android client and the spec use (duration_seconds/target_seconds,
// lat/latitude, radius_m/radius_meters, …).

import { PLATFORM } from './db'
import { parseJsonArray, parseJsonObject } from './json'
import { parseNotifyWindow, NotifyWindow } from './shopping'
import { DEFAULT_SHORT_SIT_SECONDS } from './taskEditor'

export interface DurationConfig {
  targetSeconds: number
}

export function parseDurationConfig(json: string): DurationConfig | null {
  const o = parseJsonObject(json)
  const target = Number(o.target_seconds ?? o.duration_seconds ?? 0)
  return target > 0 ? { targetSeconds: Math.trunc(target) } : null
}

export interface MeditationConfig {
  durationSeconds: number
  allowPause: boolean
  bellIntervalSeconds: number
  /** This platform's companion apps (lowercased exe names). */
  companionApps: string[]
  /** "Middle way" threshold in seconds (0 = disabled). */
  shortSitSeconds: number
}

export function parseMeditationConfig(json: string): MeditationConfig | null {
  const o = parseJsonObject(json)
  const durationSeconds = Math.trunc(Number(o.duration_seconds ?? o.target_seconds ?? 0))
  if (!(durationSeconds > 0)) return null
  const companionApps = parseJsonArray(JSON.stringify(o.companion_apps ?? []))
    .filter((e) => e && typeof e === 'object' && e.platform === PLATFORM && e.exe)
    .map((e) => String(e.exe).toLowerCase())
  const shortSit = Math.trunc(Number(o.short_sit_seconds ?? DEFAULT_SHORT_SIT_SECONDS))
  return {
    durationSeconds,
    allowPause: o.allow_pause === true,
    bellIntervalSeconds: Math.trunc(Number(o.bell_interval_seconds ?? 0)) || 0,
    companionApps,
    // A threshold at or beyond the full duration would be meaningless.
    shortSitSeconds: shortSit >= 1 && shortSit < durationSeconds ? shortSit : 0,
  }
}

export interface GpsConfig {
  lat: number
  lng: number
  radiusM: number
  /** enter | leave | notify */
  mode: string
  minDurationM: number
  notifyWindow: NotifyWindow | null
}

export function parseGpsConfig(json: string): GpsConfig | null {
  const o = parseJsonObject(json)
  const lat = Number(o.lat ?? o.latitude)
  const lng = Number(o.lng ?? o.longitude)
  if (!Number.isFinite(lat) || !Number.isFinite(lng)) return null
  return {
    lat,
    lng,
    radiusM: Number(o.radius_m ?? o.radius_meters ?? 100) || 100,
    mode: String(o.mode ?? 'enter'),
    minDurationM: Number(o.min_duration_m ?? 0) || 0,
    notifyWindow: parseNotifyWindow(o.notify_window),
  }
}

/** The report a report task or report step points at, from its config JSON. */
export function reportIdFromConfig(json: string | null | undefined): string | null {
  const id = parseJsonObject(json).report_id
  return typeof id === 'string' && id ? id : null
}

// ── Completion alternatives (shared/CONTRACTS.md) ────────────────────

/** A GPS way to complete a task, from tasks.completion_alternatives. */
export interface GpsAlternative {
  /** Position in the completion_alternatives array. */
  index: number
  label: string | null
  config: GpsConfig
}

/**
 * The supported GPS entries of a task's completion_alternatives: mode enter
 * (default) or leave, with a valid location. Everything else is skipped.
 */
export function parseGpsAlternatives(json: string | null | undefined): GpsAlternative[] {
  const out: GpsAlternative[] = []
  parseJsonArray(json).forEach((entry, index) => {
    if (!entry || typeof entry !== 'object' || Array.isArray(entry)) return
    if (entry.verification_type !== 'gps') return
    const config = parseGpsConfig(JSON.stringify(entry.verification_config ?? {}))
    if (!config || (config.mode !== 'enter' && config.mode !== 'leave')) return
    out.push({ index, label: typeof entry.label === 'string' && entry.label ? entry.label : null, config })
  })
  return out
}

/** "Climbing gym" / "the location", for "or be at …" hints. */
export function alternativePlace(alt: GpsAlternative): string {
  return alt.label ?? 'the location'
}

/** A short "or …" phrase for a GPS alternative, e.g. "be at Climbing gym for 30 min". */
export function describeGpsAlternative(alt: GpsAlternative): string {
  const place = alternativePlace(alt)
  if (alt.config.mode === 'leave') return `leave ${place}`
  return alt.config.minDurationM > 0 ? `be at ${place} for ${alt.config.minDurationM} min` : `arrive at ${place}`
}
