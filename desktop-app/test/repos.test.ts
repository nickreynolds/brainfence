// Repository and editor SQL against a real (in-memory) SQLite database.
import { memoryDb } from './helpers'
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { completeTask, getActiveTasks, getTodaysVerificationData, updateTodaysVerificationData } from '../src/shared/repos/tasks'
import {
  applyExpiredPendingChanges, cancelPendingChange, createRule, getRule, scheduleRuleChange, draftToChanges, windowsAppRef,
} from '../src/shared/repos/blocking'
import { completeRoutine, getLastStepCompletions, getRoutineSteps } from '../src/shared/repos/routines'
import { addReportFromLibrary, getReports, getTodaysRoutineSubmissions, saveMetric, submitReport } from '../src/shared/repos/reports'
import { addDeckFromLibrary, getDecks, pickNext, recordDelivery, recordResponse, getRecentDeliveries } from '../src/shared/repos/contemplations'
import { emptyEditorState, loadEditorState, newEditableStep, saveTask } from '../src/shared/taskEditor'
import { REPORT_LIBRARY } from '../src/shared/reports'
import { CONTEMPLATION_LIBRARY } from '../src/shared/contemplationLibrary'
import { seededRandom } from '../src/shared/contemplationSchedule'

test('saveTask → loadEditorState round-trips and getActiveTasks maps it', async () => {
  const db = memoryDb()
  const id = await saveTask(db, {
    ...emptyEditorState(), title: 'Vitamins', recurrenceType: 'weekly', weeklyDays: ['fri', 'mon'],
    isBlockingCondition: true, availableFrom: '07:00', dueAt: '10:00', blockingDaysOfWeek: ['tue', 'mon'],
  })
  const [t] = await getActiveTasks(db)
  assert.equal(t.id, id)
  assert.equal(t.recurrenceType, 'daily') // multi-day weekly is stored as daily + days
  assert.equal(t.recurrenceConfig, '{"days":["mon","fri"]}')
  assert.equal(t.blockingDaysOfWeek, '["mon","tue"]')
  assert.equal(t.tags, '[]')
  assert.equal(t.verificationType, null)
  const s = await loadEditorState(db, id)
  assert.equal(s.recurrenceType, 'weekly')
  assert.deepEqual(s.weeklyDays, ['mon', 'fri'])
  assert.equal(s.isBlockingCondition, true)
})

test('completions are idempotent per occurrence; one-offs stay done', async () => {
  const db = memoryDb()
  const daily = await saveTask(db, { ...emptyEditorState(), title: 'Daily', recurrenceType: 'daily' })
  const oneOff = await saveTask(db, { ...emptyEditorState(), title: 'Once' })
  await completeTask(db, daily, '{"a":1}')
  await completeTask(db, daily, '{"a":2}')
  await completeTask(db, oneOff)
  const rows = await db.getAll('SELECT task_id FROM task_completions')
  assert.equal(rows.length, 2)
  // Yesterday's one-off completion still counts; yesterday's daily doesn't.
  db.raw.exec(`UPDATE task_completions SET completed_at = '2020-01-01T12:00:00.000Z'`)
  const tasks = await getActiveTasks(db)
  assert.equal(tasks.find((t) => t.id === oneOff)!.completedToday, true)
  assert.equal(tasks.find((t) => t.id === daily)!.completedToday, false)
  await completeTask(db, oneOff)
  assert.equal((await db.getAll('SELECT 1 FROM task_completions WHERE task_id = ?', [oneOff])).length, 1)
  await completeTask(db, daily, '{"b":1}')
  await updateTodaysVerificationData(db, daily, '{"b":2}')
  assert.equal(await getTodaysVerificationData(db, daily), '{"b":2}')
})

test('editor preserves other platforms\' companion apps and unmanaged keys', async () => {
  const db = memoryDb()
  const now = new Date().toISOString()
  db.raw.prepare(`INSERT INTO tasks (id, user_id, title, task_type, status, verification_type, verification_config, tags, blocking_rule_ids, blocking_days_of_week, created_at, updated_at)
    VALUES ('m', 'user-1', 'Meditate', 'simple', 'active', 'meditation', ?, '[]', '[]', '[]', ?, ?)`).run(
    JSON.stringify({ duration_seconds: 600, bell_interval_seconds: 300, companion_apps: [{ platform: 'android', package: 'com.calm.android' }, { platform: 'windows', exe: 'old.exe' }] }),
    now, now,
  )
  const s = await loadEditorState(db, 'm')
  assert.deepEqual(s.companionApps, [{ platform: 'windows', exe: 'old.exe' }])
  await saveTask(db, { ...s, meditationSeconds: 900, companionApps: [windowsAppRef('Calm.exe')] })
  const saved = JSON.parse((await db.getOptional<any>('SELECT verification_config FROM tasks WHERE id = ?', ['m'])).verification_config)
  assert.equal(saved.duration_seconds, 900)
  assert.equal(saved.bell_interval_seconds, 300)
  assert.deepEqual(saved.companion_apps, [{ platform: 'android', package: 'com.calm.android' }, { platform: 'windows', exe: 'calm.exe' }])
})

test('routine steps diff-sync keeps ids and history', async () => {
  const db = memoryDb()
  const a = { ...newEditableStep(), title: 'Bench', stepType: 'weight_reps', defaultSets: 3 }
  const b = { ...newEditableStep(), title: 'Plank', stepType: 'timed', durationSeconds: 45 }
  const id = await saveTask(db, { ...emptyEditorState(), title: 'Push', taskType: 'workout', routineSteps: [a, b] })
  await completeRoutine(db, id, { [a.id]: ['{"weight":100,"reps":10}', '{"weight":100,"reps":8}'] })
  const s = await loadEditorState(db, id)
  await saveTask(db, { ...s, routineSteps: [{ ...s.routineSteps[1], title: 'Plank hold' }, s.routineSteps[0]] })
  const steps = await getRoutineSteps(db, id)
  assert.deepEqual(steps.map((x) => [x.id, x.title, x.stepOrder]), [[b.id, 'Plank hold', 0], [a.id, 'Bench', 1]])
  assert.equal(steps[0].config, '{"duration_seconds":45}')
  const last = await getLastStepCompletions(db, id)
  assert.deepEqual(last.map((c) => [c.setNumber, c.data]), [[1, '{"weight":100,"reps":10}'], [2, '{"weight":100,"reps":8}']])
})

test('rule changes: weakening is time-locked, tightening applies now', async () => {
  const db = memoryDb()
  const draft = { name: 'Morning', blockedApps: [windowsAppRef('discord.exe')], blockedDomains: ['reddit.com'], conditionTaskIds: ['t1'], conditionLogic: 'all', isActive: true }
  const id = await createRule(db, 'user-1', draft)
  const tighter = await scheduleRuleChange(db, id, draftToChanges({ ...draft, blockedDomains: ['reddit.com', 'x.com'] }))
  assert.equal(tighter.applied, 'now')
  assert.deepEqual((await getRule(db, id))!.blockedDomains, ['reddit.com', 'x.com'])

  const looser = await scheduleRuleChange(db, id, draftToChanges({ ...draft, blockedDomains: [] }))
  assert.equal(looser.applied, 'later')
  let r = (await getRule(db, id))!
  assert.deepEqual(r.blockedDomains, ['reddit.com', 'x.com'])
  assert.ok(r.pendingChanges)
  assert.equal(await applyExpiredPendingChanges(db), 0)
  db.raw.exec(`UPDATE blocking_rules SET changes_apply_at = '2000-01-01T00:00:00.000Z'`)
  assert.equal(await applyExpiredPendingChanges(db), 1)
  r = (await getRule(db, id))!
  assert.deepEqual(r.blockedDomains, [])
  assert.equal(r.pendingChanges, null)
  assert.deepEqual(r.blockedApps, ['discord.exe'])

  await scheduleRuleChange(db, id, draftToChanges({ ...draft, isActive: false }))
  await cancelPendingChange(db, id)
  r = (await getRule(db, id))!
  assert.equal(r.isActive, true)
  assert.equal(r.pendingChanges, null)
})

test('pending changes written double-encoded (old Android builds) still apply', async () => {
  const db = memoryDb()
  const id = await createRule(db, 'user-1', { name: 'R', blockedApps: [], blockedDomains: ['a.com'], conditionTaskIds: [], conditionLogic: 'all', isActive: true })
  db.raw.prepare(`UPDATE blocking_rules SET pending_changes = ?, changes_apply_at = '2000-01-01T00:00:00Z'`).run(JSON.stringify(JSON.stringify({ blocked_domains: [] })))
  assert.equal(await applyExpiredPendingChanges(db), 1)
  assert.deepEqual((await getRule(db, id))!.blockedDomains, [])
})

test('reports: starter library, metric edits keep option ids, routine submissions', async () => {
  const db = memoryDb()
  const reportId = await addReportFromLibrary(db, REPORT_LIBRARY[0])
  let [report] = await getReports(db)
  assert.equal(report.id, reportId)
  assert.deepEqual(report.metrics.map((m) => m.name), ['Depression', 'Anxiety', 'Happiness'])
  const m = report.metrics[0]
  await saveMetric(db, m.id, 'Low mood', null, 'ordinal', [{ id: m.options[4].id, label: 'Very' }, { id: null, label: 'Bit' }])
  ;[report] = await getReports(db)
  assert.deepEqual(report.metrics[0].options.map((o) => [o.id === m.options[4].id, o.label]), [[true, 'Very'], [false, 'Bit']])
  const answers = Object.fromEntries(report.metrics.map((x) => [x.id, x.options[0].id]))
  await submitReport(db, report, answers, '  ', 'routine', 'step-1')
  const subs = await getTodaysRoutineSubmissions(db, 'routine')
  assert.equal(subs['step-1'].entries.length, 3)
  assert.equal(subs['step-1'].note, null)
})

test('contemplations: library deck, sequential delivery, first response wins', async () => {
  const db = memoryDb()
  await addDeckFromLibrary(db, CONTEMPLATION_LIBRARY[0])
  const [deck] = await getDecks(db)
  assert.equal(deck.itemCount, 5)
  const first = (await pickNext(db, seededRandom(1)))!
  assert.equal(first.item.title, 'The First Remembrance')
  const id = await recordDelivery(db, first)
  db.raw.exec(`UPDATE contemplation_deliveries SET delivered_at = '2000-01-01T00:00:00Z'`)
  assert.equal((await pickNext(db, seededRandom(1)))!.item.title, 'The Second Remembrance')
  await recordResponse(db, id, 'affirmed')
  await recordResponse(db, id, 'other')
  assert.equal((await getRecentDeliveries(db))[0].response, 'affirmed')
})
