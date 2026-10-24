// Ported from android/.../domain/blocking/BlockingEvaluatorTest.kt, plus the
// cross-platform rule handling that only exists on Windows.
import { at, rule, task } from './helpers'
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { evaluateBlocking } from '../src/shared/blocking'
import { mapRule } from '../src/shared/repos/blocking'

const manual = (overrides = {}) => task({ id: 'task-2', title: 'Manual task', availableFrom: null, dueAt: null, ...overrides })
const blocked = (r: ReturnType<typeof evaluateBlocking>) => r.blockedApps
const at16 = (h: number) => at('2026-04-16', h)

test('time window drives blocking', () => {
  assert.deepEqual(blocked(evaluateBlocking([rule()], [task()], at16(6))), [])
  assert.deepEqual(blocked(evaluateBlocking([rule()], [task()], at16(8))), [])
  assert.deepEqual(blocked(evaluateBlocking([rule()], [task()], at16(11))), ['discord.exe'])
  assert.deepEqual(blocked(evaluateBlocking([rule()], [task({ completedToday: true })], at16(11))), [])
  assert.deepEqual(blocked(evaluateBlocking([rule()], [task({ completedToday: true })], at16(15))), [])
})

test('all / any logic with mixed tasks', () => {
  const both = { conditionTaskIds: ['task-1', 'task-2'] }
  assert.deepEqual(blocked(evaluateBlocking([rule({ ...both, conditionLogic: 'all' })], [task(), manual()], at16(8))), ['discord.exe'])
  assert.deepEqual(blocked(evaluateBlocking([rule({ ...both, conditionLogic: 'any' })], [task(), manual()], at16(8))), [])
  assert.deepEqual(blocked(evaluateBlocking([rule({ ...both, conditionLogic: 'all' })], [task(), manual()], at16(11))), ['discord.exe'])
})

test('home-only blocking', () => {
  const home = task({ homeOnlyBlocking: true })
  assert.deepEqual(blocked(evaluateBlocking([rule()], [home], at16(11), 'AT_HOME')), ['discord.exe'])
  assert.deepEqual(blocked(evaluateBlocking([rule()], [home], at16(11), 'AWAY')), [])
  assert.deepEqual(blocked(evaluateBlocking([rule()], [home], at16(11), 'UNKNOWN')), ['discord.exe'])
  assert.deepEqual(blocked(evaluateBlocking([rule()], [home], at16(11), 'UNCONFIGURED')), ['discord.exe'])
  assert.deepEqual(blocked(evaluateBlocking([rule()], [task()], at16(11), 'AWAY')), ['discord.exe'])
  const both = rule({ conditionTaskIds: ['task-1', 'task-2'] })
  assert.deepEqual(blocked(evaluateBlocking([both], [home, manual()], at16(11), 'AWAY')), ['discord.exe'])
  assert.deepEqual(blocked(evaluateBlocking([both], [home, task({ id: 'task-2', homeOnlyBlocking: true })], at16(11), 'AWAY')), [])
})

test('blocking days of week', () => {
  // 2026-04-16 is a Thursday.
  assert.deepEqual(blocked(evaluateBlocking([rule()], [task({ blockingDaysOfWeek: '["mon","tue"]' })], at16(11))), [])
  assert.deepEqual(blocked(evaluateBlocking([rule()], [task({ blockingDaysOfWeek: '["thu"]' })], at16(11))), ['discord.exe'])
})

test('unknown condition tasks fail closed', () => {
  assert.deepEqual(blocked(evaluateBlocking([rule({ conditionTaskIds: ['missing'] })], [], at16(11))), ['discord.exe'])
  const anyRule = rule({ conditionTaskIds: ['task-1', 'ghost'], conditionLogic: 'any' })
  assert.deepEqual(blocked(evaluateBlocking([anyRule], [task({ completedToday: true })], at16(11))), [])
})

test('inactive rules and rules without conditions', () => {
  assert.deepEqual(blocked(evaluateBlocking([rule({ isActive: false })], [task()], at16(11))), [])
  // No conditions = always blocking while active (Android: isConditionMet false).
  assert.deepEqual(blocked(evaluateBlocking([rule({ conditionTaskIds: [] })], [], at16(11))), ['discord.exe'])
})

test('domains and unmet tasks are reported', () => {
  const r = evaluateBlocking([rule({ blockedDomains: ['reddit.com'] })], [task()], at16(11))
  assert.deepEqual(r.blockedDomains, ['reddit.com'])
  assert.deepEqual(r.unmetTaskIdsByApp['discord.exe'], ['task-1'])
  assert.deepEqual(r.unmetTaskIds, ['task-1'])
})

test('rules read only this platform\'s apps, keeping the others', () => {
  const r = mapRule({
    id: 'r', user_id: 'u', name: 'n', condition_logic: 'all', is_active: 1, config_lock_hours: 24,
    blocked_apps: JSON.stringify([
      { platform: 'android', package: 'com.twitter.android' },
      { platform: 'windows', exe: 'Discord.exe', name: 'Discord' },
      'com.legacy.app',
    ]),
    blocked_domains: '["https://www.Reddit.com/r/all"]',
    condition_task_ids: '{a,b}',
  })
  assert.deepEqual(r.blockedApps, ['discord.exe'])
  assert.equal(r.blockedAppRefs.length, 3)
  assert.deepEqual(r.blockedDomains, ['reddit.com'])
  assert.deepEqual(r.conditionTaskIds, ['a', 'b'])
})
