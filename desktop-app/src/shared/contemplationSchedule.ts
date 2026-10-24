// Port of domain/contemplation/ContemplationSchedule.kt. Delivery times are
// device-local, so the PRNG doesn't need to match Kotlin's — only the shape of
// the schedule (one slot per equal segment, away from segment edges) does.

import type { DeckOrdering } from './models'
import { LocalDate, atTime, localDateOf, plusDays, toEpochDay } from './time'

export interface ContemplationScheduleConfig {
  perDay: number
  /** Seconds of day. */
  windowStart: number
  /** Seconds of day. */
  windowEnd: number
}

/** Fraction of each segment kept clear at either end. */
const SLOT_MARGIN = 0.2

export type Random = () => number

/** Deterministic PRNG (mulberry32) — same seed, same sequence. */
export function seededRandom(seed: number): Random {
  let a = seed >>> 0
  return () => {
    a = (a + 0x6d2b79f5) >>> 0
    let t = a
    t = Math.imul(t ^ (t >>> 15), t | 1)
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61)
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}

/**
 * Delivery times (seconds of day) for [date]: the window split into perDay
 * segments with one moment drawn from the middle of each. Deterministic for a
 * date and seed, so the day's times survive restarts without being stored.
 */
export function contemplationSlotsForDay(
  date: LocalDate,
  config: ContemplationScheduleConfig,
  seed: number,
): number[] {
  if (config.perDay <= 0 || config.windowEnd <= config.windowStart) return []
  const windowSeconds = config.windowEnd - config.windowStart
  const segment = windowSeconds / config.perDay
  const random = seededRandom(Math.imul(seed, 31) + toEpochDay(date))
  const slots: number[] = []
  for (let i = 0; i < config.perDay; i++) {
    const within = SLOT_MARGIN + random() * (1 - 2 * SLOT_MARGIN)
    const offset = Math.floor((i + within) * segment)
    slots.push(config.windowStart + Math.min(offset, windowSeconds - 1))
  }
  return slots
}

/** The first slot strictly after [now] (today's, else tomorrow's), or null. */
export function nextContemplationSlot(now: Date, config: ContemplationScheduleConfig, seed: number): Date | null {
  for (let dayOffset = 0; dayOffset <= 1; dayOffset++) {
    const date = plusDays(localDateOf(now), dayOffset)
    const next = contemplationSlotsForDay(date, config, seed)
      .map((s) => atTime(date, s))
      .find((d) => d.getTime() > now.getTime())
    if (next) return next
  }
  return null
}

/** Weighted choice: index into [weights], each share proportional to its value. */
export function pickWeightedIndex(weights: number[], random: Random): number {
  if (weights.length === 0) throw new Error('No weights to pick from')
  const clamped = weights.map((w) => Math.max(1, Math.trunc(w)))
  let roll = Math.floor(random() * clamped.reduce((a, b) => a + b, 0))
  for (let i = 0; i < clamped.length; i++) {
    if (roll < clamped[i]) return i
    roll -= clamped[i]
  }
  return clamped.length - 1
}

/** Index of the next item to deliver from a deck. */
export function pickItemIndex(
  ordering: DeckOrdering,
  itemIds: string[],
  lastDeliveredId: string | null,
  random: Random,
): number {
  if (itemIds.length === 0) throw new Error('Deck has no items')
  const lastIndex = lastDeliveredId == null ? -1 : itemIds.indexOf(lastDeliveredId)
  if (ordering === 'sequential') return lastIndex < 0 ? 0 : (lastIndex + 1) % itemIds.length
  if (itemIds.length === 1 || lastIndex < 0) return Math.floor(random() * itemIds.length)
  // Draw from the others, skipping over the last-delivered slot.
  const i = Math.floor(random() * (itemIds.length - 1))
  return i >= lastIndex ? i + 1 : i
}
