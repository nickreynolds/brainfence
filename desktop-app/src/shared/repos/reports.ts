// Port of data/report/ReportRepository.kt.

import { Db, SqlStatement, bool, requireUserId } from '../db'
import { nowIso, uuid } from '../json'
import type { Metric, MetricEntry, MetricKind, Report, ReportSubmission } from '../models'
import { StarterReport, buildMetricEntries } from '../reports'

export const REPORT_DEFINITION_TABLES = ['metrics', 'metric_options', 'reports', 'report_metrics']
export const REPORT_SUBMISSION_TABLES = ['report_submissions', 'metric_entries']

const TODAY = "date(s.submitted_at, 'localtime') = date('now', 'localtime')"

/** An option being edited: id null = new. */
export interface OptionDraft {
  id: string | null
  label: string
}

const toKind = (k: unknown): MetricKind => (k === 'categorical' ? 'categorical' : 'ordinal')

async function getMetricMap(db: Db): Promise<Map<string, Metric>> {
  const options = await db.getAll('SELECT id, metric_id, label, sort_order FROM metric_options ORDER BY sort_order, created_at')
  const byMetric = new Map<string, Metric['options']>()
  for (const o of options) {
    const list = byMetric.get(o.metric_id) ?? []
    list.push({ id: o.id, metricId: o.metric_id, label: o.label ?? '', sortOrder: Number(o.sort_order ?? 0) })
    byMetric.set(o.metric_id, list)
  }
  const metrics = await db.getAll('SELECT id, name, description, kind FROM metrics ORDER BY created_at')
  return new Map(
    metrics.map((m) => [
      m.id,
      { id: m.id, name: m.name ?? '', description: m.description ?? null, kind: toKind(m.kind), options: byMetric.get(m.id) ?? [] },
    ]),
  )
}

export async function getMetrics(db: Db): Promise<Metric[]> {
  return [...(await getMetricMap(db)).values()].sort((a, b) => a.name.toLowerCase().localeCompare(b.name.toLowerCase()))
}

export async function getReports(db: Db): Promise<Report[]> {
  const metrics = await getMetricMap(db)
  const membership = await db.getAll('SELECT report_id, metric_id FROM report_metrics ORDER BY sort_order, created_at')
  const byReport = new Map<string, string[]>()
  for (const m of membership) byReport.set(m.report_id, [...(byReport.get(m.report_id) ?? []), m.metric_id])
  const reports = await db.getAll('SELECT id, name, include_note, library_key FROM reports ORDER BY created_at')
  return reports.map((r) => ({
    id: r.id,
    name: r.name ?? '',
    includeNote: Number(r.include_note ?? 1) !== 0,
    libraryKey: r.library_key ?? null,
    metrics: (byReport.get(r.id) ?? []).map((id) => metrics.get(id)).filter((m): m is Metric => m != null),
  }))
}

export async function getReport(db: Db, reportId: string): Promise<Report | null> {
  return (await getReports(db)).find((r) => r.id === reportId) ?? null
}

export async function createReport(db: Db, name: string): Promise<string> {
  const userId = await requireUserId(db)
  const id = uuid()
  const now = nowIso()
  await db.execute(
    `INSERT INTO reports (id, user_id, name, include_note, library_key, created_at, updated_at)
     VALUES (?, ?, ?, 1, NULL, ?, ?)`,
    [id, userId, name, now, now],
  )
  return id
}

export async function updateReport(db: Db, reportId: string, name: string, includeNote: boolean): Promise<void> {
  await db.execute('UPDATE reports SET name = ?, include_note = ?, updated_at = ? WHERE id = ?', [name, bool(includeNote), nowIso(), reportId])
}

/** Deletes a report and its metric list (metrics are shared and kept). Past submissions stay. */
export async function deleteReport(db: Db, reportId: string): Promise<void> {
  await db.batch([
    { sql: 'DELETE FROM report_metrics WHERE report_id = ?', params: [reportId] },
    { sql: 'DELETE FROM reports WHERE id = ?', params: [reportId] },
  ])
}

export async function addMetricToReport(db: Db, reportId: string, metricId: string): Promise<void> {
  const userId = await requireUserId(db)
  await db.execute(
    `INSERT INTO report_metrics (id, user_id, report_id, metric_id, sort_order, created_at)
     SELECT ?, ?, ?, ?, (SELECT COALESCE(MAX(sort_order), -1) + 1 FROM report_metrics WHERE report_id = ?), ?
     WHERE NOT EXISTS (SELECT 1 FROM report_metrics WHERE report_id = ? AND metric_id = ?)`,
    [uuid(), userId, reportId, metricId, reportId, nowIso(), reportId, metricId],
  )
}

export async function removeMetricFromReport(db: Db, reportId: string, metricId: string): Promise<void> {
  await db.execute('DELETE FROM report_metrics WHERE report_id = ? AND metric_id = ?', [reportId, metricId])
}

/** Move a metric up (delta -1) or down (+1) within a report. */
export async function moveMetricInReport(db: Db, reportId: string, metricId: string, delta: number): Promise<void> {
  const rows = await db.getAll<{ id: string; metric_id: string }>(
    'SELECT id, metric_id FROM report_metrics WHERE report_id = ? ORDER BY sort_order, created_at',
    [reportId],
  )
  const from = rows.findIndex((r) => r.metric_id === metricId)
  const to = from + delta
  if (from < 0 || to < 0 || to >= rows.length) return
  const [moved] = rows.splice(from, 1)
  rows.splice(to, 0, moved)
  await db.batch(rows.map((r, i) => ({ sql: 'UPDATE report_metrics SET sort_order = ? WHERE id = ?', params: [i, r.id] })))
}

function createMetricStatements(userId: string, metricId: string, name: string, description: string | null, kind: MetricKind, labels: string[]): SqlStatement[] {
  const now = nowIso()
  return [
    {
      sql: `INSERT INTO metrics (id, user_id, name, description, kind, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)`,
      params: [metricId, userId, name, description, kind, now, now],
    },
    ...labels.map((label, index) => ({
      sql: `INSERT INTO metric_options (id, user_id, metric_id, label, sort_order, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)`,
      params: [uuid(), userId, metricId, label, index, now, now],
    })),
  ]
}

export async function createMetric(db: Db, name: string, description: string | null, kind: MetricKind, labels: string[]): Promise<string> {
  const userId = await requireUserId(db)
  const metricId = uuid()
  await db.batch(createMetricStatements(userId, metricId, name, description, kind, labels))
  return metricId
}

/**
 * Saves a metric and its options in order. Existing options keep their ids
 * (history stays linked through renames and reorders); missing ones are deleted.
 */
export async function saveMetric(db: Db, metricId: string, name: string, description: string | null, kind: MetricKind, options: OptionDraft[]): Promise<void> {
  const userId = await requireUserId(db)
  const now = nowIso()
  const existing = new Set((await db.getAll<{ id: string }>('SELECT id FROM metric_options WHERE metric_id = ?', [metricId])).map((r) => r.id))
  const keep = new Set(options.map((o) => o.id).filter((id): id is string => id != null))
  const statements: SqlStatement[] = [
    { sql: 'UPDATE metrics SET name = ?, description = ?, kind = ?, updated_at = ? WHERE id = ?', params: [name, description, kind, now, metricId] },
  ]
  for (const id of existing) if (!keep.has(id)) statements.push({ sql: 'DELETE FROM metric_options WHERE id = ?', params: [id] })
  options.forEach((option, index) => {
    if (option.id && existing.has(option.id)) {
      statements.push({ sql: 'UPDATE metric_options SET label = ?, sort_order = ?, updated_at = ? WHERE id = ?', params: [option.label, index, now, option.id] })
    } else {
      statements.push({
        sql: `INSERT INTO metric_options (id, user_id, metric_id, label, sort_order, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)`,
        params: [uuid(), userId, metricId, option.label, index, now, now],
      })
    }
  })
  await db.batch(statements)
}

/** Deletes a metric everywhere. Recorded answers keep their snapshots. */
export async function deleteMetric(db: Db, metricId: string): Promise<void> {
  await db.batch([
    { sql: 'DELETE FROM report_metrics WHERE metric_id = ?', params: [metricId] },
    { sql: 'DELETE FROM metric_options WHERE metric_id = ?', params: [metricId] },
    { sql: 'DELETE FROM metrics WHERE id = ?', params: [metricId] },
  ])
}

/** Copies a starter report and its metrics into the user's tables. */
export async function addReportFromLibrary(db: Db, starter: StarterReport): Promise<string> {
  const userId = await requireUserId(db)
  const now = nowIso()
  const reportId = uuid()
  const statements: SqlStatement[] = []
  const metricIds = starter.metrics.map((m) => {
    const id = uuid()
    statements.push(...createMetricStatements(userId, id, m.name, m.description, m.kind, m.options))
    return id
  })
  statements.push({
    sql: `INSERT INTO reports (id, user_id, name, include_note, library_key, created_at, updated_at) VALUES (?, ?, ?, 1, ?, ?, ?)`,
    params: [reportId, userId, starter.name, starter.key, now, now],
  })
  metricIds.forEach((metricId, index) => {
    statements.push({
      sql: `INSERT INTO report_metrics (id, user_id, report_id, metric_id, sort_order, created_at) VALUES (?, ?, ?, ?, ?, ?)`,
      params: [uuid(), userId, reportId, metricId, index, now],
    })
  })
  await db.batch(statements)
  return reportId
}

/**
 * Records a filled-in report: one submission plus an entry per answer.
 * [answers] maps metric id → option id and must cover every metric.
 */
export async function submitReport(
  db: Db,
  report: Report,
  answers: Record<string, string>,
  note: string | null,
  taskId: string | null,
  routineStepId: string | null,
): Promise<string> {
  const entries = buildMetricEntries(report, answers)
  const userId = await requireUserId(db)
  const now = nowIso()
  const submissionId = uuid()
  const trimmedNote = note?.trim() ? note.trim() : null
  await db.batch([
    {
      sql: `INSERT INTO report_submissions
              (id, user_id, report_id, task_id, routine_step_id, report_name, note, submitted_at, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`,
      params: [submissionId, userId, report.id, taskId, routineStepId, report.name, trimmedNote, now, now],
    },
    ...entries.map((e, position) => ({
      sql: `INSERT INTO metric_entries
              (id, user_id, submission_id, metric_id, option_id, metric_name, metric_kind,
               option_label, option_index, option_count, position, recorded_at, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
      params: [uuid(), userId, submissionId, e.metricId, e.optionId, e.metricName, e.metricKind, e.optionLabel, e.optionIndex, e.optionCount, position, now, now],
    })),
  ])
  return submissionId
}

/** Today's submission from the report task [taskId] (not from routine steps). */
export async function getTodaysTaskSubmission(db: Db, taskId: string): Promise<ReportSubmission | null> {
  return (await getSubmissions(db, `s.task_id = ? AND s.routine_step_id IS NULL AND ${TODAY}`, [taskId]))[0] ?? null
}

/** Today's submissions from the report steps of routine [taskId], keyed by step id. */
export async function getTodaysRoutineSubmissions(db: Db, taskId: string): Promise<Record<string, ReportSubmission>> {
  const subs = await getSubmissions(db, `s.task_id = ? AND s.routine_step_id IS NOT NULL AND ${TODAY}`, [taskId])
  const out: Record<string, ReportSubmission> = {}
  // Oldest first so the newest wins.
  for (const s of [...subs].reverse()) out[s.routineStepId!] = s
  return out
}

export async function getRecentSubmissions(db: Db, limit = 50): Promise<ReportSubmission[]> {
  return getSubmissions(db, '1 = 1', [], limit)
}

async function getSubmissions(db: Db, where: string, params: unknown[], limit?: number): Promise<ReportSubmission[]> {
  const rows = await db.getAll(
    `SELECT s.id, s.report_id, s.task_id, s.routine_step_id, s.report_name, s.note, s.submitted_at
     FROM report_submissions s WHERE ${where} ORDER BY s.submitted_at DESC${limit ? ` LIMIT ${Math.trunc(limit)}` : ''}`,
    params,
  )
  if (rows.length === 0) return []
  const entryRows = await db.getAll(
    `SELECT submission_id, metric_id, option_id, metric_name, metric_kind, option_label, option_index, option_count
     FROM metric_entries WHERE submission_id IN (${rows.map(() => '?').join(',')}) ORDER BY position`,
    rows.map((r) => r.id),
  )
  const bySubmission = new Map<string, MetricEntry[]>()
  for (const e of entryRows) {
    const list = bySubmission.get(e.submission_id) ?? []
    list.push({
      metricId: e.metric_id ?? null,
      optionId: e.option_id ?? null,
      metricName: e.metric_name ?? '',
      metricKind: toKind(e.metric_kind),
      optionLabel: e.option_label ?? '',
      optionIndex: Number(e.option_index ?? 0),
      optionCount: Number(e.option_count ?? 0),
    })
    bySubmission.set(e.submission_id, list)
  }
  return rows.map((r) => ({
    id: r.id,
    reportId: r.report_id ?? null,
    taskId: r.task_id ?? null,
    routineStepId: r.routine_step_id ?? null,
    reportName: r.report_name ?? '',
    note: r.note ?? null,
    submittedAt: r.submitted_at ?? '',
    entries: bySubmission.get(r.id) ?? [],
  }))
}
