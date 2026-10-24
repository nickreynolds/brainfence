// Runs the cross-platform cases in shared/fixtures, which the Android and macOS
// test suites load too. See shared/CONTRACTS.md.
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { parseInstant } from '../src/shared/json'
import type { AppRef } from '../src/shared/models'
import { appRefKey, isMoreRestrictive, mapRule, normalizeDomain, parseBlockedApps } from '../src/shared/repos/blocking'

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
