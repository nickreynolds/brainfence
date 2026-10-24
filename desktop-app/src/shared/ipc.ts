// Contract between the renderer (window.api, see src/preload) and the main process.

import type { BlockingState, HomePresence } from './blocking'
import type { SqlStatement } from './db'

export type AuthState =
  | { status: 'loading' }
  | { status: 'signedOut' }
  | { status: 'signedIn'; userId: string; email: string | null }

export interface SyncStatusInfo {
  connected: boolean
  connecting: boolean
  hasSynced: boolean
  lastSyncedAt: string | null
  uploading: boolean
  downloading: boolean
  error: string | null
}

export interface DurationTimerState {
  taskId: string
  taskTitle: string
  targetSeconds: number
  elapsedSeconds: number
  running: boolean
}

export interface MeditationTimerState {
  taskId: string
  taskTitle: string
  targetSeconds: number
  elapsedSeconds: number
  running: boolean
  pauses: number
  /** in_app_timer | companion_app */
  method: string
  companionApp: string | null
  shortSitSeconds: number
  shortSitIntent: boolean
  shortSitBanked: boolean
}

export interface TimerStates {
  duration: Record<string, DurationTimerState>
  meditation: Record<string, MeditationTimerState>
}

export interface InstalledApp {
  /** Lowercased executable file name — the identifier stored in blocked_apps. */
  exe: string
  name: string
  path: string | null
  running: boolean
}

export interface HomeLocation {
  latitude: number
  longitude: number
  radiusMeters: number
}

export type LocationAccess = 'allowed' | 'denied' | 'unavailable' | 'unknown'

export interface LocationFix {
  latitude: number
  longitude: number
  accuracyM: number | null
  at: string
}

export interface LocationStatus {
  access: LocationAccess
  lastFix: LocationFix | null
  error: string | null
}

export interface ContemplationSettings {
  enabled: boolean
  perDay: number
  windowStart: string
  windowEnd: string
}

/** Website blocking via the Windows hosts file. */
export interface WebsiteBlockingStatus {
  /** Whether Brainfence can write the hosts file without elevation. */
  writable: boolean
  /** Domains currently written to the hosts file. */
  activeDomains: string[]
  error: string | null
}

export interface EnforcementInfo {
  state: BlockingState
  homePresence: HomePresence
  /** Foreground app exe (lowercased), for the debug screen. */
  foregroundExe: string | null
  website: WebsiteBlockingStatus
  evaluatedAt: string
}

export interface AppSettings {
  launchAtLogin: boolean
  /** Minimize blocked windows as well as covering them. */
  minimizeBlockedWindows: boolean
}

export interface DebugLogEntry {
  id: string
  timestamp: string
  category: string
  message: string
  data: string | null
}

export interface GpsCheckResult {
  ok: boolean
  message: string
}

export interface BrainfenceApi {
  db: {
    getAll(sql: string, params?: unknown[]): Promise<any[]>
    getOptional(sql: string, params?: unknown[]): Promise<any | null>
    execute(sql: string, params?: unknown[]): Promise<void>
    batch(statements: SqlStatement[]): Promise<void>
    userId(): Promise<string | null>
    onChange(cb: (tables: string[]) => void): () => void
  }
  auth: {
    getState(): Promise<AuthState>
    signIn(email: string, password: string): Promise<void>
    signUp(email: string, password: string): Promise<{ needsConfirmation: boolean }>
    signOut(): Promise<void>
    onState(cb: (state: AuthState) => void): () => void
  }
  sync: {
    getStatus(): Promise<SyncStatusInfo>
    onStatus(cb: (status: SyncStatusInfo) => void): () => void
  }
  blocking: {
    getInfo(): Promise<EnforcementInfo>
    onInfo(cb: (info: EnforcementInfo) => void): () => void
    /** Saved changes may complete tasks / alter rules: re-evaluate now. */
    reevaluate(): Promise<void>
    setupWebsiteBlocking(): Promise<WebsiteBlockingStatus>
  }
  timers: {
    getStates(): Promise<TimerStates>
    onStates(cb: (states: TimerStates) => void): () => void
    duration(action: 'start' | 'pause' | 'resume' | 'cancel', taskId: string): Promise<void>
    meditation(action: 'startInApp' | 'startShortSit' | 'startCompanion' | 'pause' | 'resume' | 'finishEarly' | 'cancel', taskId: string): Promise<void>
  }
  apps: {
    list(): Promise<InstalledApp[]>
    icon(path: string): Promise<string | null>
  }
  location: {
    getStatus(): Promise<LocationStatus>
    refresh(): Promise<LocationStatus>
    openSettings(): Promise<void>
    onStatus(cb: (status: LocationStatus) => void): () => void
  }
  gps: {
    checkTask(taskId: string): Promise<GpsCheckResult>
  }
  settings: {
    getHome(): Promise<HomeLocation | null>
    setHome(home: HomeLocation | null): Promise<void>
    getContemplation(): Promise<ContemplationSettings>
    setContemplation(settings: ContemplationSettings): Promise<void>
    sendContemplationNow(): Promise<boolean>
    getApp(): Promise<AppSettings>
    setApp(settings: AppSettings): Promise<void>
  }
  kv: {
    get(key: string): Promise<any>
    set(key: string, value: any): Promise<void>
  }
  debug: {
    getLogs(category?: string | null): Promise<DebugLogEntry[]>
    clear(): Promise<void>
    log(category: string, message: string, data?: string): Promise<void>
    previewOverlay(): Promise<void>
  }
  window: {
    /** Open (or focus) the main window, optionally at a route like "task/<id>". */
    openMain(route?: string): Promise<void>
    onNavigate(cb: (route: string) => void): () => void
    /** The overlay's blocked exe, when running in the blocking overlay window. */
    overlayContext(): Promise<{ exe: string; name: string } | null>
    dismissOverlay(): Promise<void>
    playChime(): void
    quit(): Promise<void>
  }
}

declare global {
  interface Window {
    api: BrainfenceApi
  }
}
