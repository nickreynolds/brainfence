// Ported from android/.../domain/recurrence/RecurrenceEngineTest.kt and TimeGatePhaseTest.kt.
import { at } from './helpers'
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { computeNextOccurrence, computeOccurrenceStatus, computeTaskPhase, OccurrenceStatus } from '../src/shared/recurrence'
import { isoDate, localDateOf, parseTaskTime } from '../src/shared/time'

const status = (type: string | null, config: string, now: Date, last: Date | null = null, from: string | null = null, due: string | null = null) =>
  computeOccurrenceStatus(type, config, from, due, last, now)

const nextDate = (s: OccurrenceStatus) => {
  assert.equal(s.kind, 'Upcoming')
  return isoDate(localDateOf((s as { nextDueAt: Date }).nextDueAt))
}

test('one-off: due until completed, then completed', () => {
  assert.equal(status(null, '{}', at('2026-04-16')).kind, 'Due')
  assert.equal(status(null, '{}', at('2026-04-16'), at('2026-04-10')).kind, 'Completed')
})

test('daily: due, completed today, never completed', () => {
  assert.equal(status('daily', '{}', at('2026-04-16'), at('2026-04-15')).kind, 'Due')
  assert.equal(status('daily', '{}', at('2026-04-16', 14), at('2026-04-16', 8)).kind, 'Completed')
  assert.equal(status('daily', '{}', at('2026-04-16')).kind, 'Due')
})

test('daily with specific days', () => {
  // 2026-04-16 is a Thursday, 04-15 a Wednesday
  assert.equal(status('daily', '{"days": ["mon", "thu", "fri"]}', at('2026-04-16')).kind, 'Due')
  assert.equal(nextDate(status('daily', '{"days": ["mon", "thu", "fri"]}', at('2026-04-15'))), '2026-04-16')
})

test('weekly', () => {
  assert.equal(status('weekly', '{"day": "thu"}', at('2026-04-16')).kind, 'Due')
  assert.equal(status('weekly', '{"day": "thu"}', at('2026-04-16', 14), at('2026-04-16', 8)).kind, 'Completed')
  assert.equal(nextDate(status('weekly', '{"day": "thu"}', at('2026-04-15'))), '2026-04-16')
  // After this week's day: next week.
  assert.equal(nextDate(status('weekly', '{"day": "mon"}', at('2026-04-16'))), '2026-04-20')
})

test('weekly with interval 2 follows epoch-week parity like the Android engine', () => {
  // epochDay(2026-04-16) = 20559 → epochWeek 2937 (odd) → off-week for interval 2.
  const s = status('weekly', '{"day": "thu", "interval": 2}', at('2026-04-16'))
  assert.equal(s.kind, 'Upcoming')
  assert.equal(nextDate(s), '2026-04-23')
})

test('monthly', () => {
  // 2nd Wednesday of April 2026 = April 8; of May = May 13
  assert.equal(status('monthly', '{"week": 2, "day": "wed"}', at('2026-04-08')).kind, 'Due')
  assert.equal(nextDate(status('monthly', '{"week": 2, "day": "wed"}', at('2026-04-06'))), '2026-04-08')
  assert.equal(nextDate(status('monthly', '{"week": 2, "day": "wed"}', at('2026-04-16'))), '2026-05-13')
})

test('interval', () => {
  assert.equal(status('interval', '{"minutes": 480}', at('2026-04-16')).kind, 'Due')
  assert.equal(status('interval', '{"minutes": 480}', at('2026-04-16', 18), at('2026-04-16', 9)).kind, 'Due')
  const s = status('interval', '{"minutes": 480}', at('2026-04-16', 12), at('2026-04-16', 9))
  assert.equal(s.kind, 'Upcoming')
  assert.equal((s as any).nextDueAt.getTime(), at('2026-04-16', 17).getTime())
  const window = '{"minutes": 60, "active_days": ["mon", "tue", "wed", "thu", "fri"], "active_start": "09:00", "active_end": "17:00"}'
  assert.equal(status('interval', window, at('2026-04-18', 20)).kind, 'NotDue') // Saturday
  assert.equal(status('interval', '{"minutes": 60, "active_start": "09:00", "active_end": "17:00"}', at('2026-04-16', 20)).kind, 'NotDue')
  assert.equal(status('interval', window, at('2026-04-16', 12)).kind, 'Due')
})

test('availability window', () => {
  assert.equal(status('daily', '{}', at('2026-04-16', 6), null, '07:00', '10:00').kind, 'NotDue')
  assert.equal(status('daily', '{}', at('2026-04-16', 8), null, '07:00', '10:00').kind, 'Due')
  // Past due_at the task is still completable.
  assert.equal(status('daily', '{}', at('2026-04-16', 11), null, '07:00', '10:00').kind, 'Due')
  assert.equal(status('daily', '{}', at('2026-04-16', 9), at('2026-04-16', 8), '07:00', '10:00').kind, 'Completed')
})

test('unknown recurrence type is not due', () => {
  assert.equal(status('fortnightly', '{}', at('2026-04-16')).kind, 'NotDue')
})

test('next occurrence', () => {
  const day = (d: Date | null) => (d ? isoDate(localDateOf(d)) : null)
  assert.equal(day(computeNextOccurrence(null, '{}', null, at('2026-04-16'))), null)
  assert.equal(day(computeNextOccurrence('daily', '{}', null, at('2026-04-16'))), '2026-04-17')
  assert.equal(day(computeNextOccurrence('daily', '{"days": ["mon"]}', null, at('2026-04-16'))), '2026-04-20')
  assert.equal(day(computeNextOccurrence('weekly', '{"day": "thu"}', null, at('2026-04-16'))), '2026-04-23')
  assert.equal(day(computeNextOccurrence('monthly', '{"week": 2, "day": "wed"}', null, at('2026-04-16'))), '2026-05-13')
})

test('time gate phases', () => {
  const p = (from: string | null, due: string | null, h: number, m = 0) => computeTaskPhase(from, due, at('2026-04-16', h, m))
  assert.equal(p('07:00', '10:00', 6, 59), 'BEFORE_START')
  assert.equal(p('07:00', '10:00', 7), 'ACTIVE')
  assert.equal(p('07:00', '10:00', 8), 'ACTIVE')
  assert.equal(p('07:00', '10:00', 10), 'PAST_DUE')
  assert.equal(p('07:00', '10:00', 11), 'PAST_DUE')
  assert.equal(p(null, null, 8), null)
  assert.equal(p('07:00', null, 6), 'BEFORE_START')
  assert.equal(p('07:00', null, 8), 'ACTIVE')
  assert.equal(p(null, '10:00', 8), 'ACTIVE')
  assert.equal(p(null, '10:00', 11), 'PAST_DUE')
})

test('task times parse tolerantly', () => {
  assert.equal(parseTaskTime('9:00'), 9 * 3600)
  assert.equal(parseTaskTime('09:05'), 9 * 3600 + 300)
  assert.equal(parseTaskTime(''), null)
  assert.equal(parseTaskTime('25:00'), null)
  assert.equal(parseTaskTime('9am'), null)
})
