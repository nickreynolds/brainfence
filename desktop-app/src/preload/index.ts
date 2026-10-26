import { contextBridge, ipcRenderer } from 'electron'
import type { BrainfenceApi } from '../shared/ipc'

const invoke = (channel: string, ...args: unknown[]) => ipcRenderer.invoke(channel, ...args)

function subscribe<T>(channel: string, cb: (value: T) => void): () => void {
  const handler = (_e: Electron.IpcRendererEvent, value: T) => cb(value)
  ipcRenderer.on(channel, handler)
  return () => ipcRenderer.removeListener(channel, handler)
}

const api: BrainfenceApi = {
  db: {
    getAll: (sql, params) => invoke('db:getAll', sql, params),
    getOptional: (sql, params) => invoke('db:getOptional', sql, params),
    execute: (sql, params) => invoke('db:execute', sql, params),
    batch: (statements) => invoke('db:batch', statements),
    userId: () => invoke('db:userId'),
    onChange: (cb) => subscribe('db:changed', cb),
  },
  auth: {
    getState: () => invoke('auth:getState'),
    signIn: (email, password) => invoke('auth:signIn', email, password),
    signUp: (email, password) => invoke('auth:signUp', email, password),
    signOut: () => invoke('auth:signOut'),
    deleteAccount: () => invoke('auth:deleteAccount'),
    onState: (cb) => subscribe('auth:state', cb),
    takeNotice: () => invoke('auth:takeNotice'),
    onNotice: (cb) => subscribe('auth:notice', cb),
  },
  sync: {
    getStatus: () => invoke('sync:getStatus'),
    onStatus: (cb) => subscribe('sync:status', cb),
  },
  blocking: {
    getInfo: () => invoke('blocking:getInfo'),
    onInfo: (cb) => subscribe('blocking:info', cb),
    reevaluate: () => invoke('blocking:reevaluate'),
    setupWebsiteBlocking: () => invoke('blocking:setupWebsite'),
  },
  timers: {
    getStates: () => invoke('timers:getStates'),
    onStates: (cb) => subscribe('timers:states', cb),
    duration: (action, taskId) => invoke('timers:duration', action, taskId),
    meditation: (action, taskId) => invoke('timers:meditation', action, taskId),
  },
  apps: {
    list: () => invoke('apps:list'),
    icon: (path) => invoke('apps:icon', path),
  },
  location: {
    getStatus: () => invoke('location:getStatus'),
    refresh: () => invoke('location:refresh'),
    openSettings: () => invoke('location:openSettings'),
    onStatus: (cb) => subscribe('location:status', cb),
  },
  gps: {
    checkTask: (taskId) => invoke('gps:checkTask', taskId),
  },
  settings: {
    getHome: () => invoke('settings:getHome'),
    setHome: (home) => invoke('settings:setHome', home),
    getContemplation: () => invoke('settings:getContemplation'),
    setContemplation: (s) => invoke('settings:setContemplation', s),
    sendContemplationNow: () => invoke('settings:sendContemplationNow'),
    getApp: () => invoke('settings:getApp'),
    setApp: (s) => invoke('settings:setApp', s),
  },
  kv: {
    get: (key) => invoke('kv:get', key),
    set: (key, value) => invoke('kv:set', key, value),
  },
  debug: {
    getLogs: (category) => invoke('debug:getLogs', category),
    clear: () => invoke('debug:clear'),
    log: (category, message, data) => invoke('debug:log', category, message, data),
    previewOverlay: () => invoke('debug:previewOverlay'),
  },
  window: {
    openMain: (route) => invoke('window:openMain', route),
    onNavigate: (cb) => subscribe('window:navigate', cb),
    overlayContext: () => invoke('window:overlayContext'),
    dismissOverlay: () => invoke('window:dismissOverlay'),
    playChime: () => ipcRenderer.send('window:playChime'),
    quit: () => invoke('window:quit'),
  },
}

contextBridge.exposeInMainWorld('api', api)
contextBridge.exposeInMainWorld('overlayEvents', {
  onContext: (cb: (ctx: { exe: string; name: string }) => void) => subscribe('overlay:context', cb),
})
