// Runs the cross-platform cases in shared/fixtures, which the Android and macOS
// test suites load too. See shared/CONTRACTS.md.
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { parseInstant } from '../src/shared/json'
import type { AppRef } from '../src/shared/models'
import { appRefKey, isMoreRestrictive, mapRule, normalizeDomain, parseBlockedApps } from '../src/shared/repos/blocking'
import { mapOnboarding } from '../src/shared/repos/onboarding'
import { buildOnboardingPlan, onboardingRoute } from '../src/shared/onboarding'
import { parseAuthCallback } from '../src/shared/authCallback'
import { FEEDBACK_MAX_LENGTH, codePointLength, normalizeFeedback } from '../src/shared/feedback'
import { HomePresence, isConditionUnmet } from '../src/shared/blocking'
import { computeTaskStatus } from '../src/shared/recurrence'
import { parseGpsAlternatives } from '../src/shared/verification'
import { parseTimedSetsConfig, timedSetsHangCount, timedSetsRestAfter, timedSetsSetOf, timedSetsSideOf } from '../src/shared/timedSets'
import { task as baseTask } from './helpers'

const fixture = (name: string) => JSON.parse(readFileSync(join(__dirname, '../../shared/fixtures', name), 'utf8'))

test('rule restrictiveness', () => {
  const { base_rule, cases } = fixture('rule-restrictiveness.json')
  for (const c of cases) {
    const row = { ...base_rule, ...c.rule }
    const rule = mapRule({
      id: 'r', name: 'R', ...row,
      blocked_apps: JSON.stringify(row.blocked_apps),
      blocked_domains: JSON.stringify(row.blocked_domains),
      condition_task_ids: JSON.stringify(row.condition_task_ids),
    })
    assert.equal(isMoreRestrictive(rule, c.changes), c.more_restrictive, c.name)
  }
})

test('blocked app keys', () => {
  for (const c of fixture('blocked-app-keys.json').cases) {
    const [ref] = parseBlockedApps(JSON.stringify([c.entry])) as (AppRef | undefined)[]
    assert.equal(ref ? appRefKey(ref) : null, c.key, JSON.stringify(c.entry))
  }
})

test('domain normalization', () => {
  for (const c of fixture('domains.json').normalize) {
    assert.equal(normalizeDomain(c.input) || null, c.normalized, c.input)
  }
})

test('timestamps', () => {
  for (const c of fixture('timestamps.json').cases) {
    assert.equal(parseInstant(c.input)?.toISOString() ?? null, c.utc, c.input)
  }
})

test('onboarding route', () => {
  for (const c of fixture('onboarding.json').route) {
    const onboarding = c.onboarding && mapOnboarding({ ...c.onboarding, deferred_steps: JSON.stringify(c.onboarding.deferred_steps) })
    const route = onboardingRoute({ platform: c.platform, hasSynced: c.has_synced, onboarding, taskCount: c.task_count })
    assert.equal(route, c.route, c.name)
  }
})

test('onboarding plan', () => {
  for (const c of fixture('onboarding.json').plan) {
    const plan = buildOnboardingPlan(c.choices)
    const tasks = plan.tasks.map((t) => ({ ...t, steps: t.steps.map((s) => ({ title: s.title, step_type: s.stepType, config: s.config })) }))
    assert.deepEqual({ ...plan, tasks }, c.expect, c.name)
  }
})

test('auth callback links', () => {
  for (const c of fixture('auth-callback.json').cases) {
    assert.deepEqual(parseAuthCallback(c.url), c.result, c.url)
  }
})

test('feedback messages', () => {
  const { max_length, cases } = fixture('feedback.json')
  assert.equal(FEEDBACK_MAX_LENGTH, max_length)
  assert.ok(cases.length > 0)
  for (const c of cases) {
    const message: string = c.repeat ? c.repeat.text.repeat(c.repeat.times) + c.repeat.suffix : c.message
    const actual = normalizeFeedback(message)
    if ('expect_length' in c) {
      assert.equal(actual, message.trim(), c.name)
      assert.equal(codePointLength(actual!), c.expect_length, c.name)
    } else {
      assert.equal(actual, c.expect, c.name)
    }
  }
})

test('task status', () => {
  const { time_zone, base_task, cases } = fixture('task-status.json')
  process.env.TZ = time_zone
  assert.ok(cases.length > 0)
  for (const c of cases) {
    const row = { ...base_task, ...c.task }
    const t = baseTask({
      recurrenceType: row.recurrence_type,
      recurrenceConfig: JSON.stringify(row.recurrence_config),
      carryOver: row.carry_over === 1,
      availableFrom: row.available_from,
      dueAt: row.due_at,
      homeOnlyBlocking: row.home_only_blocking === 1,
      blockingDaysOfWeek: JSON.stringify(row.blocking_days_of_week),
      createdAt: row.created_at,
      lastCompletionAt: c.last_completion_at ?? null,
    })
    const now = new Date(c.now)
    const status = computeTaskStatus(t, now)
    assert.deepEqual(
      {
        scheduled: status.scheduled,
        completed: status.completed,
        carried_over: status.carriedOver,
        phase: status.phase,
        unmet: isConditionUnmet(t, now, c.home as HomePresence),
      },
      c.expect,
      c.name,
    )
  }
})

test('completion alternatives', () => {
  for (const c of fixture('completion-alternatives.json').cases) {
    const actual = parseGpsAlternatives(JSON.stringify(c.input)).map((a) => ({
      index: a.index, label: a.label, lat: a.config.lat, lng: a.config.lng,
      radius_m: a.config.radiusM, mode: a.config.mode, min_duration_m: a.config.minDurationM,
    }))
    assert.deepEqual(actual, c.gps, c.name)
  }
})

test('timed sets', () => {
  for (const c of fixture('timed-sets.json').cases) {
    const config = parseTimedSetsConfig(JSON.stringify(c.config))
    const hangs = timedSetsHangCount(config)
    assert.equal(hangs, c.hangs, c.name)
    assert.equal(config.weight, c.weight, c.name)
    assert.equal(config.weightPrompt, c.weight_prompt, c.name)
    const each = (f: (i: number) => number) => Array.from({ length: hangs }, (_, i) => f(i))
    assert.deepEqual(each((i) => timedSetsSetOf(config, i)), c.set_of, c.name)
    assert.deepEqual(each((i) => timedSetsSideOf(config, i)), c.side_of, c.name)
    assert.deepEqual(each((i) => timedSetsRestAfter(config, i, c.last_step)), c.rest_after, c.name)
  }
})
