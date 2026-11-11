// timed_sets steps: shared/CONTRACTS.md (Timed sets), fixtures/timed-sets.json.

import { parseJsonObject } from './json'

/** "added": weight added to bodyweight, negative = assisted. "load": the weight lifted. */
export type WeightKind = 'added' | 'load'
/** When the runner asks for the weight: once before the routine, or before each set. */
export type WeightPrompt = 'start' | 'each_set'

export interface TimedSetsConfig {
  /** Hangs per side per set. */
  reps: number
  workSeconds: number
  /** Rest between hangs. */
  restSeconds: number
  sets: number
  /** 1, or 2 for one arm at a time (left, then right). */
  sides: number
  /** Rest between the left and right halves of a set. */
  sideRestSeconds: number
  /** Rest after each set, including before the next step. */
  setRestSeconds: number
  weight: WeightKind | null
  weightPrompt: WeightPrompt
}

export function parseTimedSetsConfig(json: string | null | undefined): TimedSetsConfig {
  const c = parseJsonObject(json)
  const int = (v: unknown, fallback: number, min: number) => {
    const n = Math.trunc(Number(v))
    return v != null && Number.isFinite(n) && n >= min ? n : fallback
  }
  const restSeconds = int(c.rest_seconds, 20, 0)
  return {
    reps: int(c.reps, 1, 1),
    workSeconds: int(c.work_seconds, 10, 1),
    restSeconds,
    sets: int(c.sets, 1, 1),
    sides: c.sides === 2 ? 2 : 1,
    sideRestSeconds: int(c.side_rest_seconds, restSeconds, 0),
    setRestSeconds: int(c.set_rest_seconds, restSeconds, 0),
    weight: c.weight === 'added' || c.weight === 'load' ? c.weight : null,
    weightPrompt: c.weight_prompt === 'each_set' ? 'each_set' : 'start',
  }
}

/** Hangs (entries) per set: both sides. */
export function timedSetsHangsPerSet(config: TimedSetsConfig): number {
  return config.reps * config.sides
}

/** Number of hangs (entries) in the step. */
export function timedSetsHangCount(config: TimedSetsConfig): number {
  return config.sets * timedSetsHangsPerSet(config)
}

/** 0-based set a hang belongs to. */
export function timedSetsSetOf(config: TimedSetsConfig, hangIndex: number): number {
  return Math.floor(hangIndex / timedSetsHangsPerSet(config))
}

/** 0-based side a hang is on (0 = left). */
export function timedSetsSideOf(config: TimedSetsConfig, hangIndex: number): number {
  return Math.floor(hangIndex / config.reps) % config.sides
}

/** Whether hang [hangIndex] starts a set. */
export function timedSetsStartsSet(config: TimedSetsConfig, hangIndex: number): boolean {
  return hangIndex % timedSetsHangsPerSet(config) === 0
}

/** Rest after hang [hangIndex]; 0 after the routine's final hang. */
export function timedSetsRestAfter(config: TimedSetsConfig, hangIndex: number, isLastStep: boolean): number {
  if (isLastStep && hangIndex === timedSetsHangCount(config) - 1) return 0
  if ((hangIndex + 1) % timedSetsHangsPerSet(config) === 0) return config.setRestSeconds
  if ((hangIndex + 1) % config.reps === 0) return config.sideRestSeconds
  return config.restSeconds
}

/** "+10 lb", "10 lb assisted", "Bodyweight" (added) or "25 lb" (load). */
export function formatTimedWeight(kind: WeightKind | null, weight: number | null): string {
  const text = (w: number) => (Number.isInteger(w) ? String(w) : w.toFixed(1))
  if (kind === 'load') return weight == null ? 'No weight set' : `${text(weight)} lb`
  if (weight == null || weight === 0) return 'Bodyweight'
  return weight > 0 ? `+${text(weight)} lb` : `${text(-weight)} lb assisted`
}
