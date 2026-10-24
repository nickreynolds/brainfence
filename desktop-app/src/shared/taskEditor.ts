// Port of ui/tasks/TaskEditorViewModel.kt's state and save logic.
//
// One deliberate difference: Android rewrites verification_config wholesale on
// save, which drops keys its editor doesn't know (another platform's companion
// apps, an automatic routine's execution_mode). Here, when the task type is
// unchanged, unmanaged keys are carried over and only this platform's
// companion-app entries are replaced.

import { Db, PLATFORM, SqlStatement, bool, requireUserId } from './db'
import { nowIso, parseJsonArray, parseJsonObject, parseStringArray, uuid } from './json'
import type { AppRef, Report } from './models'
import { getRoutineSteps, insertStepStatement } from './repos/routines'
import { getTaskRow } from './repos/tasks'

export const DEFAULT_SHORT_SIT_SECONDS = 120

export interface EditableStep {
  id: string
  title: string
  stepType: string
  defaultSets: number
  durationSeconds: number
  supersetGroup: string | null
  reportId: string | null
  description: string
}

export function newEditableStep(): EditableStep {
  return { id: uuid(), title: '', stepType: 'checkbox', defaultSets: 3, durationSeconds: 60, supersetGroup: null, reportId: null, description: '' }
}

export interface TaskEditorState {
  title: string
  description: string
  /** simple | timed | routine | workout | journal | shopping | report */
  taskType: string
  /** manual | duration | gps | meditation | journal | report */
  verificationType: string
  durationSeconds: number
  latitude: string
  longitude: string
  radiusMeters: number
  gpsMode: string
  gpsMinDurationM: number
  notifyDays: string[]
  notifyStart: string
  notifyEnd: string
  meditationSeconds: number
  shortSitSeconds: number
  /** This platform's companion apps (Windows exe names). */
  companionApps: AppRef[]
  reportId: string | null
  routineSteps: EditableStep[]
  /** null | daily | weekly (UI form; multi-day weekly is stored as daily+days). */
  recurrenceType: string | null
  weeklyDays: string[]
  isBlockingCondition: boolean
  homeOnlyBlocking: boolean
  blockingDaysOfWeek: string[]
  availableFrom: string
  dueAt: string
  editingTaskId: string | null
  /** Loaded task's type, verification type and config, for carrying unmanaged keys over. */
  originalTaskType: string | null
  originalVerificationType: string | null
  originalVerificationConfig: Record<string, any>
}

export function emptyEditorState(): TaskEditorState {
  return {
    title: '', description: '', taskType: 'simple', verificationType: 'manual',
    durationSeconds: 300, latitude: '', longitude: '', radiusMeters: 100, gpsMode: 'enter', gpsMinDurationM: 0,
    notifyDays: ['mon', 'tue', 'wed', 'thu', 'fri'], notifyStart: '15:00', notifyEnd: '21:00',
    meditationSeconds: 300, shortSitSeconds: DEFAULT_SHORT_SIT_SECONDS, companionApps: [],
    reportId: null, routineSteps: [], recurrenceType: null, weeklyDays: [],
    isBlockingCondition: false, homeOnlyBlocking: false, blockingDaysOfWeek: [], availableFrom: '', dueAt: '',
    editingTaskId: null, originalTaskType: null, originalVerificationType: null, originalVerificationConfig: {},
  }
}

/** Verification type implied by a task type when switching types. */
export function verificationForType(type: string, current: string): string {
  switch (type) {
    case 'timed': return 'duration'
    case 'routine': case 'workout': return 'manual'
    case 'journal': return 'journal'
    case 'shopping': return 'gps'
    case 'report': return 'report'
    default: return current === 'journal' || current === 'report' || current === 'duration' ? 'manual' : current
  }
}

export const isRoutineType = (t: string) => t === 'routine' || t === 'workout'
/** Shopping tasks skip the schedule step — they never recur, block, or expire. */
export const totalSteps = (s: TaskEditorState) => (s.taskType === 'shopping' ? 2 : 3)

export async function loadEditorState(db: Db, taskId: string): Promise<TaskEditorState> {
  const row = await getTaskRow(db, taskId)
  if (!row) throw new Error('Task not found')
  const base = emptyEditorState()
  const taskType: string = row.task_type ?? 'simple'
  const verificationType: string | null = row.verification_type ?? null
  const v = parseJsonObject(row.verification_config)
  const r = parseJsonObject(row.recurrence_config)
  const notifyWindow = taskType === 'shopping' && v.notify_window && typeof v.notify_window === 'object' ? v.notify_window : null

  let recurrenceType: string | null = null
  let weeklyDays: string[] = []
  if (row.recurrence_type === 'weekly') {
    recurrenceType = 'weekly'
    weeklyDays = typeof r.day === 'string' && r.day ? [r.day] : []
  } else if (row.recurrence_type === 'daily') {
    const days = Array.isArray(r.days) ? r.days.map(String) : []
    if (days.length > 0) {
      recurrenceType = 'weekly'
      weeklyDays = days
    } else recurrenceType = 'daily'
  } else if (row.recurrence_type) {
    // monthly / interval have no editor UI yet; keep them as-is.
    recurrenceType = row.recurrence_type
  }

  const steps = isRoutineType(taskType)
    ? (await getRoutineSteps(db, taskId)).map((s) => {
        const c = parseJsonObject(s.config)
        return {
          id: s.id,
          title: s.title,
          stepType: s.stepType,
          defaultSets: num(c.default_sets ?? c.reps, 3),
          durationSeconds: num(c.duration_seconds ?? c.work_seconds, 60),
          supersetGroup: s.supersetGroup,
          reportId: typeof c.report_id === 'string' && c.report_id ? c.report_id : null,
          description: s.description ?? '',
        }
      })
    : []

  const isGps = verificationType === 'gps'
  const isMeditation = verificationType === 'meditation'
  return {
    ...base,
    title: row.title ?? '',
    description: row.description ?? '',
    taskType,
    verificationType: verificationType ?? (taskType === 'timed' ? 'duration' : 'manual'),
    durationSeconds: taskType === 'timed' ? num(v.duration_seconds ?? v.target_seconds, 300) : 300,
    latitude: isGps ? String(num(v.latitude ?? v.lat, 0)) : '',
    longitude: isGps ? String(num(v.longitude ?? v.lng, 0)) : '',
    radiusMeters: isGps ? num(v.radius_meters ?? v.radius_m, 100) : 100,
    gpsMode: isGps ? String(v.mode ?? 'enter') : 'enter',
    gpsMinDurationM: isGps ? num(v.min_duration_m, 0) : 0,
    notifyDays: notifyWindow && Array.isArray(notifyWindow.days) ? notifyWindow.days.map(String) : base.notifyDays,
    notifyStart: notifyWindow ? String(notifyWindow.start ?? '') : base.notifyStart,
    notifyEnd: notifyWindow ? String(notifyWindow.end ?? '') : base.notifyEnd,
    meditationSeconds: isMeditation ? num(v.duration_seconds, 300) : 300,
    shortSitSeconds: isMeditation ? num(v.short_sit_seconds, DEFAULT_SHORT_SIT_SECONDS) : DEFAULT_SHORT_SIT_SECONDS,
    companionApps: isMeditation ? parseJsonArray(JSON.stringify(v.companion_apps ?? [])).filter((a) => a && typeof a === 'object' && a.platform === PLATFORM) : [],
    reportId: taskType === 'report' && typeof v.report_id === 'string' && v.report_id ? v.report_id : null,
    routineSteps: steps,
    recurrenceType,
    weeklyDays,
    isBlockingCondition: Number(row.is_blocking_condition ?? 0) !== 0,
    homeOnlyBlocking: Number(row.home_only_blocking ?? 0) !== 0,
    blockingDaysOfWeek: parseStringArray(row.blocking_days_of_week),
    availableFrom: row.available_from ?? '',
    dueAt: row.due_at ?? '',
    editingTaskId: taskId,
    originalTaskType: taskType,
    originalVerificationType: verificationType ?? (taskType === 'timed' ? 'duration' : 'manual'),
    originalVerificationConfig: v,
  }
}

/** Validation for advancing past wizard step [step]; null if OK. */
export function validateStep(s: TaskEditorState, step: number): string | null {
  if (step === 0 && s.title.trim() === '') return 'Title is required'
  if (step === 1 && isRoutineType(s.taskType)) {
    if (s.routineSteps.length === 0) return 'Add at least one step'
    if (s.routineSteps.some((st) => st.title.trim() === '')) return 'All steps must have a title'
  }
  return null
}

export function validateForSave(s: TaskEditorState, reports: Report[]): string | null {
  if (s.title.trim() === '') return 'Title is required'
  if (s.taskType === 'shopping' && (!isNum(s.latitude) || !isNum(s.longitude))) {
    return 'A reminder location (latitude/longitude) is required'
  }
  if (s.verificationType === 'gps' && s.taskType !== 'shopping' && (!isNum(s.latitude) || !isNum(s.longitude))) {
    return 'Latitude and longitude are required for GPS verification'
  }
  if (s.taskType === 'report' && !s.reportId) return 'Choose which report this task asks'
  if (s.taskType === 'report' && s.reportId && !reports.some((r) => r.id === s.reportId)) return 'That report no longer exists'
  if (isRoutineType(s.taskType) && s.routineSteps.some((st) => st.stepType === 'report' && !st.reportId)) {
    return 'Choose a report for each report step'
  }
  const err = validateStep(s, 1)
  if (err) return err
  for (const [label, value] of [['Available from', s.availableFrom], ['Due at', s.dueAt]] as const) {
    if (value.trim() !== '' && !/^\d{1,2}:\d{2}$/.test(value.trim())) return `${label} must be HH:mm`
  }
  return null
}

export function stepConfigJson(step: EditableStep): Record<string, unknown> {
  switch (step.stepType) {
    case 'weight_reps':
    case 'just_reps':
      return { default_sets: step.defaultSets }
    case 'timed':
      return { duration_seconds: step.durationSeconds }
    case 'timed_sets':
      return { reps: step.defaultSets, work_seconds: step.durationSeconds, rest_seconds: 20 }
    case 'report':
      return step.reportId ? { report_id: step.reportId } : {}
    default:
      return {}
  }
}

export function buildVerificationConfig(s: TaskEditorState): Record<string, unknown> {
  const sameKind = s.originalTaskType === s.taskType && s.originalVerificationType === s.verificationType
  const carried: Record<string, any> = sameKind ? { ...s.originalVerificationConfig } : {}
  if (s.taskType === 'timed') return { ...carried, duration_seconds: s.durationSeconds }
  if (s.taskType === 'report') return { ...carried, report_id: s.reportId }
  if (s.taskType === 'shopping') {
    const window: Record<string, unknown> = { days: s.notifyDays }
    if (s.notifyStart.trim()) window.start = s.notifyStart.trim()
    if (s.notifyEnd.trim()) window.end = s.notifyEnd.trim()
    return {
      latitude: Number(s.latitude) || 0, longitude: Number(s.longitude) || 0, radius_meters: s.radiusMeters,
      mode: 'notify', notify_window: window,
    }
  }
  if (isRoutineType(s.taskType)) return carried
  if (s.verificationType === 'gps') {
    // The spec's lat/lng/radius_m spellings are rewritten as the editor's.
    const { lat: _lat, lng: _lng, radius_m: _r, ...rest } = carried
    return {
      ...rest,
      latitude: Number(s.latitude) || 0, longitude: Number(s.longitude) || 0, radius_meters: s.radiusMeters,
      mode: s.gpsMode, min_duration_m: s.gpsMode === 'enter' ? s.gpsMinDurationM : 0,
    }
  }
  if (s.verificationType === 'meditation') {
    const others = parseJsonArray(JSON.stringify(carried.companion_apps ?? []))
      .filter((a) => typeof a === 'string' || (a && typeof a === 'object' && a.platform !== PLATFORM))
    return {
      ...carried,
      duration_seconds: s.meditationSeconds,
      short_sit_seconds: s.shortSitSeconds,
      companion_apps: [...others, ...s.companionApps],
    }
  }
  return {}
}

export function buildRecurrenceConfig(s: TaskEditorState, original: Record<string, any>): Record<string, unknown> {
  if (s.recurrenceType === 'weekly' && s.weeklyDays.length > 0) {
    return s.weeklyDays.length === 1 ? { day: s.weeklyDays[0] } : { days: sortDays(s.weeklyDays) }
  }
  if (s.recurrenceType === 'monthly' || s.recurrenceType === 'interval') return original
  return {}
}

/** Insert or update the task (and diff-sync its routine steps). Returns the task id. */
export async function saveTask(db: Db, s: TaskEditorState): Promise<string> {
  const userId = await requireUserId(db)
  const now = nowIso()
  const taskType = s.taskType
  const verificationType =
    taskType === 'timed' ? 'duration'
    : isRoutineType(taskType) ? null
    : taskType === 'shopping' ? 'gps'
    : taskType === 'report' ? 'report'
    : s.verificationType === 'manual' ? null : s.verificationType
  const verificationConfig = JSON.stringify(buildVerificationConfig(s))
  const originalRow = s.editingTaskId ? await getTaskRow(db, s.editingTaskId) : null
  const recurrenceConfig = JSON.stringify(buildRecurrenceConfig(s, parseJsonObject(originalRow?.recurrence_config)))
  const effectiveRecurrence =
    taskType === 'shopping' ? null
    : s.recurrenceType === 'weekly' && s.weeklyDays.length > 1 ? 'daily'
    : s.recurrenceType === 'weekly' && s.weeklyDays.length === 0 ? null
    : s.recurrenceType
  const isBlocking = taskType !== 'shopping' && s.isBlockingCondition
  const availableFrom = taskType === 'shopping' ? null : s.availableFrom.trim() || null
  const dueAt = taskType === 'shopping' ? null : s.dueAt.trim() || null
  const blockingDays = JSON.stringify(sortDays(s.blockingDaysOfWeek))
  const description = s.description.trim() ? s.description : null

  const statements: SqlStatement[] = []
  let taskId = s.editingTaskId
  if (taskId) {
    statements.push({
      sql: `UPDATE tasks SET
              title = ?, description = ?, task_type = ?, recurrence_type = ?, recurrence_config = ?,
              verification_type = ?, verification_config = ?, is_blocking_condition = ?, available_from = ?, due_at = ?,
              home_only_blocking = ?, blocking_days_of_week = ?, updated_at = ?
            WHERE id = ?`,
      params: [
        s.title.trim(), description, taskType, effectiveRecurrence, recurrenceConfig,
        verificationType, verificationConfig, bool(isBlocking), availableFrom, dueAt,
        bool(s.homeOnlyBlocking), blockingDays, now, taskId,
      ],
    })
    if (isRoutineType(taskType)) statements.push(...(await routineStepSyncStatements(db, userId, taskId, s.routineSteps)))
  } else {
    taskId = uuid()
    statements.push({
      sql: `INSERT INTO tasks
              (id, user_id, title, description, task_type, status, recurrence_type, recurrence_config,
               verification_type, verification_config, tags, sort_order, is_blocking_condition, blocking_rule_ids,
               available_from, due_at, home_only_blocking, blocking_days_of_week, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
      // tags / blocking_rule_ids are Postgres arrays: locally a JSON array, which
      // the connector uploads as a real array ("{}" would be rejected).
      params: [
        taskId, userId, s.title.trim(), description, taskType, 'active', effectiveRecurrence, recurrenceConfig,
        verificationType, verificationConfig, '[]', 0, bool(isBlocking), '[]',
        availableFrom, dueAt, bool(s.homeOnlyBlocking), blockingDays, now, now,
      ],
    })
    if (isRoutineType(taskType)) {
      s.routineSteps.forEach((step, index) => {
        statements.push(insertStepStatement(userId, taskId!, toNewStep(step), index, step.id))
      })
    }
  }
  await db.batch(statements)
  return taskId
}

/**
 * Diff-sync routine steps in place (insert new, update existing, delete
 * removed) — step_completions cascade-delete with their step, so keeping each
 * step's id preserves the user's history.
 */
async function routineStepSyncStatements(db: Db, userId: string, taskId: string, edited: EditableStep[]): Promise<SqlStatement[]> {
  const existing = await getRoutineSteps(db, taskId)
  const existingIds = new Set(existing.map((s) => s.id))
  const editedIds = new Set(edited.map((s) => s.id))
  const statements: SqlStatement[] = existing
    .filter((s) => !editedIds.has(s.id))
    .map((s) => ({ sql: 'DELETE FROM routine_steps WHERE id = ?', params: [s.id] }))
  edited.forEach((step, index) => {
    const ns = toNewStep(step)
    if (existingIds.has(step.id)) {
      statements.push({
        sql: `UPDATE routine_steps SET title = ?, step_order = ?, step_type = ?, config = ?, superset_group = ?, description = ? WHERE id = ?`,
        params: [ns.title, index, ns.stepType, ns.config, ns.supersetGroup, ns.description ?? null, step.id],
      })
    } else {
      statements.push(insertStepStatement(userId, taskId, ns, index, step.id))
    }
  })
  return statements
}

function toNewStep(step: EditableStep) {
  return {
    title: step.title.trim(),
    stepType: step.stepType,
    config: JSON.stringify(stepConfigJson(step)),
    supersetGroup: step.supersetGroup,
    description: step.description.trim() || null,
  }
}

const DAY_ORDER = ['mon', 'tue', 'wed', 'thu', 'fri', 'sat', 'sun']
export function sortDays(days: string[]): string[] {
  return [...new Set(days)].sort((a, b) => DAY_ORDER.indexOf(a) - DAY_ORDER.indexOf(b))
}

function num(value: unknown, fallback: number): number {
  const n = typeof value === 'number' ? value : typeof value === 'string' ? Number(value) : NaN
  return Number.isFinite(n) ? n : fallback
}

const isNum = (s: string) => s.trim() !== '' && Number.isFinite(Number(s))
