// Ported from ContemplationScheduleTest.kt, ShoppingNotifyPolicyTest.kt and ReportAnswersTest.kt.
import { at } from './helpers'
import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  contemplationSlotsForDay, nextContemplationSlot, pickItemIndex, pickWeightedIndex, seededRandom,
} from '../src/shared/contemplationSchedule'
import { shouldNotifyShopping, NotifyWindow } from '../src/shared/shopping'
import { buildMetricEntries, unansweredMetrics, validateMetricDraft } from '../src/shared/reports'
import type { Report } from '../src/shared/models'

const config = { perDay: 5, windowStart: 8 * 3600, windowEnd: 21 * 3600 }
const date = { year: 2026, month: 4, day: 16 }

test('slots: perDay, sorted, inside the window, one per segment, spread out', () => {
  const slots = contemplationSlotsForDay(date, config, 42)
  assert.equal(slots.length, 5)
  assert.deepEqual([...slots].sort((a, b) => a - b), slots)
  const segment = (config.windowEnd - config.windowStart) / 5
  slots.forEach((s, i) => {
    assert.ok(s >= config.windowStart + i * segment && s < config.windowStart + (i + 1) * segment)
  })
  for (let i = 1; i < slots.length; i++) assert.ok(slots[i] - slots[i - 1] >= 0.4 * segment - 1)
})

test('slots: deterministic per date and seed, vary by day, empty when unusable', () => {
  assert.deepEqual(contemplationSlotsForDay(date, config, 42), contemplationSlotsForDay(date, config, 42))
  assert.notDeepEqual(contemplationSlotsForDay(date, config, 42), contemplationSlotsForDay({ ...date, day: 17 }, config, 42))
  assert.deepEqual(contemplationSlotsForDay(date, { ...config, perDay: 0 }, 42), [])
  assert.deepEqual(contemplationSlotsForDay(date, { ...config, windowEnd: config.windowStart }, 42), [])
})

test('next slot: first after now, else tomorrow', () => {
  const slots = contemplationSlotsForDay(date, config, 7)
  const now = at('2026-04-16', 0)
  now.setSeconds(slots[1] + 1)
  const next = nextContemplationSlot(now, config, 7)!
  assert.equal(next.getHours() * 3600 + next.getMinutes() * 60 + next.getSeconds(), slots[2])
  const late = nextContemplationSlot(at('2026-04-16', 22), config, 7)!
  assert.equal(late.getDate(), 17)
  assert.equal(nextContemplationSlot(at('2026-04-16', 9), { ...config, perDay: 0 }, 7), null)
})

test('item picking', () => {
  const ids = ['a', 'b', 'c']
  const r = seededRandom(1)
  assert.equal(pickItemIndex('sequential', ids, null, r), 0)
  assert.equal(pickItemIndex('sequential', ids, 'a', r), 1)
  assert.equal(pickItemIndex('sequential', ids, 'c', r), 0)
  assert.equal(pickItemIndex('sequential', ids, 'deleted', r), 0)
  for (let i = 0; i < 200; i++) assert.notEqual(pickItemIndex('random', ids, 'b', r), 1)
  assert.equal(pickItemIndex('random', ['only'], 'only', r), 0)
})

test('weighted pick respects proportions', () => {
  const r = seededRandom(99)
  const counts = [0, 0]
  for (let i = 0; i < 10_000; i++) counts[pickWeightedIndex([4, 1], r)]++
  const share = counts[0] / 10_000
  assert.ok(share > 0.77 && share < 0.83, `share ${share}`)
})

const commute: NotifyWindow = { days: ['mon', 'tue', 'wed', 'thu', 'fri'], start: 15 * 3600, end: 21 * 3600 }

test('shopping reminders: window gating', () => {
  assert.equal(shouldNotifyShopping(commute, null, at('2026-04-16', 17, 30)), true)
  assert.equal(shouldNotifyShopping(commute, null, at('2026-04-16', 8, 45)), false)
  assert.equal(shouldNotifyShopping(commute, null, at('2026-04-16', 22)), false)
  assert.equal(shouldNotifyShopping(commute, null, at('2026-04-16', 15)), true)
  assert.equal(shouldNotifyShopping(commute, null, at('2026-04-16', 21)), true)
  assert.equal(shouldNotifyShopping(commute, null, at('2026-04-18', 17)), false) // Saturday
  assert.equal(shouldNotifyShopping({ ...commute, days: [] }, null, at('2026-04-18', 17)), true)
})

test('shopping reminders: debounce', () => {
  assert.equal(shouldNotifyShopping(commute, at('2026-04-16', 16), at('2026-04-16', 18)), false)
  assert.equal(shouldNotifyShopping(commute, at('2026-04-15', 18), at('2026-04-16', 18)), true)
  assert.equal(shouldNotifyShopping(commute, at('2026-04-16', 9), at('2026-04-16', 18)), true)
  assert.equal(shouldNotifyShopping(null, null, at('2026-04-16', 9)), true)
  assert.equal(shouldNotifyShopping(null, at('2026-04-16', 8), at('2026-04-16', 10)), false)
  assert.equal(shouldNotifyShopping(null, at('2026-04-16', 8), at('2026-04-16', 12)), true)
  const daysOnly = { days: ['thu'], start: null, end: null }
  assert.equal(shouldNotifyShopping(daysOnly, at('2026-04-16', 8), at('2026-04-16', 10)), false)
  assert.equal(shouldNotifyShopping(commute, new Date(0), at('2026-04-16', 16)), true)
})

const report: Report = {
  id: 'r', name: 'Mood', includeNote: true, libraryKey: null,
  metrics: [
    { id: 'm1', name: 'Anxiety', description: null, kind: 'ordinal', options: [
      { id: 'o1', metricId: 'm1', label: '1', sortOrder: 0 }, { id: 'o2', metricId: 'm1', label: '2', sortOrder: 1 },
    ] },
    { id: 'm2', name: 'Nicotine', description: null, kind: 'categorical', options: [
      { id: 'p1', metricId: 'm2', label: 'None', sortOrder: 0 }, { id: 'p2', metricId: 'm2', label: 'Vape', sortOrder: 1 },
      { id: 'p3', metricId: 'm2', label: 'Patch', sortOrder: 2 },
    ] },
  ],
}

test('report entries snapshot metric and option', () => {
  const entries = buildMetricEntries(report, { m1: 'o2', m2: 'p3' })
  assert.deepEqual(entries.map((e) => [e.metricName, e.metricKind, e.optionLabel, e.optionIndex, e.optionCount]), [
    ['Anxiety', 'ordinal', '2', 1, 2],
    ['Nicotine', 'categorical', 'Patch', 2, 3],
  ])
  assert.deepEqual(unansweredMetrics(report, { m1: 'p1' }).map((m) => m.id), ['m1', 'm2'])
  assert.throws(() => buildMetricEntries(report, { m1: 'o1' }))
})

test('metric draft validation', () => {
  assert.equal(validateMetricDraft('', ['a', 'b']), 'Name is required')
  assert.equal(validateMetricDraft('x', ['a', ' ']), "Options can't be blank")
  assert.equal(validateMetricDraft('x', ['a']), 'Use 2–5 options')
  assert.equal(validateMetricDraft('x', ['a', 'b', 'c', 'd', 'e', 'f']), 'Use 2–5 options')
  assert.equal(validateMetricDraft('x', ['A', 'a ']), 'Options must be different from each other')
  assert.equal(validateMetricDraft('x', ['a', 'b']), null)
})
