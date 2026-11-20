// timed_sets steps: shared/CONTRACTS.md (Timed sets), fixtures/timed-sets.json.

import { BOARDS, BoardHold, BoardLayout } from './boards.generated'
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
  /** Hangboard (shared/boards.json) and each hand's hold; null when absent or unknown. */
  board: string | null
  leftHold: string | null
  rightHold: string | null
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
    ...parseBoardHolds(c),
  }
}

/** shared/CONTRACTS.md (Board holds): unknown boards and holds are ignored. */
function parseBoardHolds(c: Record<string, unknown>): { board: string | null; leftHold: string | null; rightHold: string | null } {
  const board = typeof c.board === 'string' ? BOARDS[c.board] : undefined
  if (!board) return { board: null, leftHold: null, rightHold: null }
  const hold = (v: unknown) => (typeof v === 'string' && board.holds.some((h) => h.id === v) ? v : null)
  return { board: board.id, leftHold: hold(c.left_hold), rightHold: hold(c.right_hold) }
}

export function boardOf(config: TimedSetsConfig): BoardLayout | null {
  return config.board ? BOARDS[config.board] ?? null : null
}

function holdOf(board: BoardLayout, id: string | null): BoardHold | null {
  return id ? board.holds.find((h) => h.id === id) ?? null : null
}

/** "Hold 5 · large flat edge", "Left: hold 10, small edge · Right: hold 9, …" (fixtures/board-holds.json). */
export function boardHoldsCaption(config: TimedSetsConfig): string | null {
  const board = boardOf(config)
  if (!board) return null
  const left = holdOf(board, config.leftHold)
  const right = holdOf(board, config.rightHold)
  const named = (h: BoardHold) => `hold ${h.number}, ${h.name}`
  if (left && right) {
    if (left.id === right.id) return `Hold ${left.number} · ${left.name} · both hands`
    if (left.number === right.number) return `Hold ${left.number} · ${left.name}`
    return `Left: ${named(left)} · Right: ${named(right)}`
  }
  if (left) return `Left hand only: ${named(left)}`
  if (right) return `Right hand only: ${named(right)}`
  return null
}

/** Weights the stepper offers, in 2.5 lb steps (CONTRACTS: Weights). */
export const WEIGHT_STEP = 2.5
export function weightRange(kind: WeightKind): { min: number; max: number } {
  return kind === 'added' ? { min: -100, max: 200 } : { min: 0, max: 500 }
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
