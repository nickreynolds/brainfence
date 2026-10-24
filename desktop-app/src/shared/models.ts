// Domain models — mirror android/app/src/main/java/dev/brainfence/domain/model.
// JSON columns (JSONB / arrays) stay as the raw strings PowerSync stores locally.

export interface Task {
  id: string
  userId: string
  title: string
  description: string | null
  taskType: string
  status: string
  recurrenceType: string | null
  recurrenceConfig: string
  verificationType: string | null
  verificationConfig: string
  tags: string
  groupId: string | null
  sortOrder: number
  isBlockingCondition: boolean
  blockingRuleIds: string
  /** HH:MM — when the task becomes completable. */
  availableFrom: string | null
  /** HH:MM — when the task becomes overdue / triggers blocking. */
  dueAt: string | null
  /** Only blocks when the user is at their configured home location. */
  homeOnlyBlocking: boolean
  /** JSON array of "mon"…"sun". Empty = block every day. */
  blockingDaysOfWeek: string
  createdAt: string
  updatedAt: string
  // Derived from task_completions — not stored columns
  completedToday: boolean
  lastCompletionAt: string | null
}

/** A platform-tagged app reference as stored in blocked_apps / companion_apps. */
export interface AppRef {
  platform: string
  /** Android package name. */
  package?: string
  /** macOS bundle id. */
  bundle_id?: string
  /** Windows executable file name, e.g. "discord.exe". */
  exe?: string
  /** Display name, for UIs on other platforms. */
  name?: string
  [key: string]: unknown
}

export interface BlockingRule {
  id: string
  userId: string
  name: string
  /** Every platform's entries, as stored. */
  blockedAppRefs: AppRef[]
  /** This platform's identifiers (lowercased exe names on Windows). */
  blockedApps: string[]
  blockedDomains: string[]
  conditionTaskIds: string[]
  conditionLogic: string
  configLockHours: number
  pendingChanges: string | null
  changesApplyAt: string | null
  isActive: boolean
}

export interface RoutineStep {
  id: string
  taskId: string
  title: string
  stepOrder: number
  /** checkbox | weight_reps | just_reps | timed | timed_sets | report */
  stepType: string
  config: string
  supersetGroup: string | null
  createdAt: string
  description: string | null
}

export interface StepCompletion {
  id: string
  taskCompletionId: string
  routineStepId: string
  setNumber: number
  data: string
  completedAt: string
}

export interface ShoppingItem {
  id: string
  userId: string
  taskId: string
  title: string
  sortOrder: number
  completedAt: string | null
  createdAt: string
}

export interface JournalEntry {
  id: string
  taskId: string
  taskTitle: string
  text: string
  completedAt: string
}

// ── Contemplations ───────────────────────────────────────────────────

export type DeckOrdering = 'sequential' | 'random'
export type ContemplationResponseType = 'none' | 'affirm'
export const AFFIRMED = 'affirmed'

export interface ContemplationDeck {
  id: string
  name: string
  description: string | null
  ordering: DeckOrdering
  responseType: ContemplationResponseType
  responseLabel: string | null
  weight: number
  isEnabled: boolean
  libraryKey: string | null
  sortOrder: number
  itemCount: number
}

export interface Contemplation {
  id: string
  deckId: string
  title: string | null
  body: string
  attribution: string | null
  sortOrder: number
}

export interface ContemplationDelivery {
  id: string
  deckId: string | null
  contemplationId: string | null
  title: string | null
  body: string
  attribution: string | null
  deliveredAt: string
  response: string | null
  respondedAt: string | null
}

// ── Reports ──────────────────────────────────────────────────────────

export type MetricKind = 'ordinal' | 'categorical'

export interface MetricOption {
  id: string
  metricId: string
  label: string
  sortOrder: number
}

export interface Metric {
  id: string
  name: string
  description: string | null
  kind: MetricKind
  options: MetricOption[]
}

export interface Report {
  id: string
  name: string
  includeNote: boolean
  libraryKey: string | null
  metrics: Metric[]
}

export interface MetricEntry {
  metricId: string | null
  optionId: string | null
  metricName: string
  metricKind: MetricKind
  optionLabel: string
  optionIndex: number
  optionCount: number
}

export interface ReportSubmission {
  id: string
  reportId: string | null
  taskId: string | null
  routineStepId: string | null
  reportName: string
  note: string | null
  submittedAt: string
  entries: MetricEntry[]
}

export const DAYS = ['mon', 'tue', 'wed', 'thu', 'fri', 'sat', 'sun'] as const
export const DAY_LABELS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun']
