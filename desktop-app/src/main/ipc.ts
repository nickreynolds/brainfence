import { app, ipcMain } from 'electron'
import type { AppSettings, ContemplationSettings, HomeLocation } from '@shared/ipc'
import type { SqlStatement } from '@shared/db'
import { getActiveTask } from '@shared/repos/tasks'
import { parseDurationConfig, parseMeditationConfig } from '@shared/verification'
import { getAuthState, onAuthState, onNotice, signIn, signOut, signUp, takeNotice, userIdForLocalWrites } from './auth'
import { appIcon, listInstalledApps } from './blocking/apps'
import { setupHostsAccess } from './blocking/hosts'
import { hideOverlay, overlayContext, overlayWebContentsId, showOverlay } from './blocking/overlay'
import { prettyName } from './blocking/apps'
import { deliverContemplation, getContemplationSettings, setContemplationSettings } from './contemplations'
import { clearLogs, getLogs, log } from './debugLog'
import { checkGpsTask } from './gps'
import { getHome, getLocationStatus, onLocationStatus, openLocationSettings, refreshLocation, setHome } from './location'
import { clearLocalData, getSyncStatus, mainDb, onSyncStatus, onTablesChanged } from './powersync'
import { evaluateNow, getEnforcementInfo, onEnforcementInfo, reevaluate } from './service'
import { playChime } from './sound'
import { getValue, setValue } from './store'
import {
  cancelDuration, cancelMeditation, finishMeditationEarly, getTimerStates, onTimerStates, pauseDuration, pauseMeditation, resumeDuration, resumeMeditation, startDuration, startMeditationInApp, startCompanionTracking,
} from './timers'
import { confirmQuit } from './tray'
import { broadcast, showMainWindow } from './windows'

export function getAppSettings(): AppSettings {
  return { launchAtLogin: app.getLoginItemSettings().openAtLogin, minimizeBlockedWindows: true }
}

export function registerIpc(): void {
  // Database
  ipcMain.handle('db:getAll', (_e, sql: string, params?: unknown[]) => mainDb.getAll(sql, params))
  ipcMain.handle('db:getOptional', (_e, sql: string, params?: unknown[]) => mainDb.getOptional(sql, params))
  ipcMain.handle('db:execute', (_e, sql: string, params?: unknown[]) => mainDb.execute(sql, params))
  ipcMain.handle('db:batch', (_e, statements: SqlStatement[]) => mainDb.batch(statements))
  ipcMain.handle('db:userId', () => userIdForLocalWrites())
  onTablesChanged((tables) => broadcast('db:changed', tables))

  // Auth & sync
  ipcMain.handle('auth:getState', () => getAuthState())
  ipcMain.handle('auth:signIn', (_e, email: string, password: string) => signIn(email, password))
  ipcMain.handle('auth:signUp', (_e, email: string, password: string) => signUp(email, password))
  ipcMain.handle('auth:signOut', async () => {
    await signOut()
    await clearLocalData()
    evaluateNow()
  })
  onAuthState((s) => broadcast('auth:state', s))
  ipcMain.handle('auth:takeNotice', () => takeNotice())
  onNotice((n) => broadcast('auth:notice', n))
  ipcMain.handle('sync:getStatus', () => getSyncStatus())
  onSyncStatus((s) => broadcast('sync:status', s))

  // Blocking
  ipcMain.handle('blocking:getInfo', () => getEnforcementInfo())
  ipcMain.handle('blocking:reevaluate', () => reevaluate())
  ipcMain.handle('blocking:setupWebsite', async () => {
    const status = await setupHostsAccess()
    await reevaluate()
    return status
  })
  onEnforcementInfo((info) => broadcast('blocking:info', info))

  // Timers
  ipcMain.handle('timers:getStates', () => getTimerStates())
  onTimerStates((s) => broadcast('timers:states', s))
  ipcMain.handle('timers:duration', async (_e, action: string, taskId: string) => {
    if (action === 'start') {
      const task = await getActiveTask(mainDb, taskId)
      const config = task && parseDurationConfig(task.verificationConfig)
      if (task && config) startDuration(task.id, task.title, config.targetSeconds)
    } else if (action === 'pause') pauseDuration(taskId)
    else if (action === 'resume') resumeDuration(taskId)
    else if (action === 'cancel') cancelDuration(taskId)
  })
  ipcMain.handle('timers:meditation', async (_e, action: string, taskId: string) => {
    if (action === 'startInApp' || action === 'startShortSit' || action === 'startCompanion') {
      const task = await getActiveTask(mainDb, taskId)
      const config = task && parseMeditationConfig(task.verificationConfig)
      if (!task || !config) return
      if (action === 'startCompanion') startCompanionTracking(task.id, task.title, config)
      else startMeditationInApp(task.id, task.title, config, action === 'startShortSit')
    } else if (action === 'pause') pauseMeditation(taskId)
    else if (action === 'resume') resumeMeditation(taskId)
    else if (action === 'finishEarly') finishMeditationEarly(taskId)
    else if (action === 'cancel') cancelMeditation(taskId)
  })

  // Apps, location, GPS
  ipcMain.handle('apps:list', () => listInstalledApps())
  ipcMain.handle('apps:icon', (_e, path: string) => appIcon(path))
  ipcMain.handle('location:getStatus', () => getLocationStatus())
  ipcMain.handle('location:refresh', async () => {
    const s = await refreshLocation()
    evaluateNow()
    return s
  })
  ipcMain.handle('location:openSettings', () => openLocationSettings())
  onLocationStatus((s) => broadcast('location:status', s))
  ipcMain.handle('gps:checkTask', (_e, taskId: string) => checkGpsTask(taskId))

  // Settings
  ipcMain.handle('settings:getHome', () => getHome())
  ipcMain.handle('settings:setHome', (_e, home: HomeLocation | null) => setHome(home))
  ipcMain.handle('settings:getContemplation', () => getContemplationSettings())
  ipcMain.handle('settings:setContemplation', (_e, s: ContemplationSettings) => setContemplationSettings(s))
  ipcMain.handle('settings:sendContemplationNow', () => deliverContemplation())
  ipcMain.handle('settings:getApp', () => getAppSettings())
  ipcMain.handle('settings:setApp', (_e, s: AppSettings) => {
    app.setLoginItemSettings({ openAtLogin: s.launchAtLogin, args: ['--hidden'] })
    setValue('launchAtLoginChosen', true)
  })

  // Local key-value state
  ipcMain.handle('kv:get', (_e, key: string) => getValue(`kv:${key}`, null))
  ipcMain.handle('kv:set', (_e, key: string, value: unknown) => setValue(`kv:${key}`, value))

  // Debug
  ipcMain.handle('debug:getLogs', (_e, category?: string | null) => getLogs(category))
  ipcMain.handle('debug:clear', () => clearLogs())
  ipcMain.handle('debug:log', (_e, category: string, message: string, data?: string) => log(category, message, data))
  // Show the overlay for a currently blocked app (or a sample) to check what it looks like.
  ipcMain.handle('debug:previewOverlay', () => {
    const exe = getEnforcementInfo().state.blockedApps[0] ?? 'example.exe'
    showOverlay(exe, prettyName(exe))
  })

  // Windows
  ipcMain.handle('window:openMain', (e, route?: string) => {
    // From the overlay's "Open Brainfence": take the overlay down first.
    if (e.sender.id === overlayWebContentsId()) hideOverlay()
    showMainWindow(route)
  })
  ipcMain.handle('window:overlayContext', () => overlayContext())
  ipcMain.handle('window:dismissOverlay', () => hideOverlay())
  ipcMain.on('window:playChime', () => playChime())
  ipcMain.handle('window:quit', () => confirmQuit())
}
