import { app, powerMonitor } from 'electron'
import { resolve } from 'node:path'
import { handleAuthCallback, initAuth } from './auth'
import { destroyOverlay } from './blocking/overlay'
import { stopEnforcer } from './blocking/enforcer'
import { APP_USER_MODEL_ID, PROTOCOL } from './config'
import { handleProtocolUrl, rescheduleContemplations } from './contemplations'
import { registerIpc } from './ipc'
import { closeDatabase, initDatabase } from './powersync'
import { onEnforcementInfo, reevaluate, scheduleLocationCheckSoon, startService } from './service'
import { flushStore, getValue, setValue } from './store'
import { persistAllTimers, restoreTimers } from './timers'
import { createTray, updateTray } from './tray'
import { createMainWindow, setQuitting, showMainWindow } from './windows'

if (!app.requestSingleInstanceLock()) {
  app.quit()
} else {
  app.setAppUserModelId(APP_USER_MODEL_ID)
  // brainfence:// links (contemplation toast buttons, email confirmation links) come back through here.
  // Dev runs set BRAINFENCE_REGISTER_PROTOCOL=1 to opt in, since registering
  // points the user's registry at node_modules' electron.exe.
  if (app.isPackaged) app.setAsDefaultProtocolClient(PROTOCOL)
  else if (process.env.BRAINFENCE_REGISTER_PROTOCOL === '1') {
    app.setAsDefaultProtocolClient(PROTOCOL, process.execPath, [resolve(process.argv[1] ?? '.')])
  }

  const launchedHidden = process.argv.includes('--hidden')

  app.on('second-instance', (_event, argv) => {
    const url = argv.find((a) => a.startsWith(`${PROTOCOL}://`))
    if (url) void routeProtocolUrl(url)
    else showMainWindow()
  })

  app.whenReady().then(async () => {
    registerIpc()
    await initDatabase()
    initAuth()
    createMainWindow(!launchedHidden)
    createTray()
    onEnforcementInfo(updateTray)
    restoreTimers()
    await startService()
    rescheduleContemplations()

    // First packaged run: start with Windows, like Android's boot receiver.
    if (app.isPackaged && !getValue('launchAtLoginChosen', false)) {
      app.setLoginItemSettings({ openAtLogin: true, args: ['--hidden'] })
      setValue('launchAtLoginChosen', true)
    }

    const url = process.argv.find((a) => a.startsWith(`${PROTOCOL}://`))
    if (url) void routeProtocolUrl(url)

    powerMonitor.on('resume', () => {
      rescheduleContemplations()
      void reevaluate()
      scheduleLocationCheckSoon()
    })
    powerMonitor.on('unlock-screen', () => void reevaluate())
  })

  // Keep running in the tray when the window closes.
  app.on('window-all-closed', () => undefined)

  app.on('before-quit', () => {
    setQuitting()
    persistAllTimers()
    flushStore()
    stopEnforcer()
    destroyOverlay()
    void closeDatabase()
  })
}

async function routeProtocolUrl(url: string): Promise<void> {
  if (await handleProtocolUrl(url)) return
  if (await handleAuthCallback(url)) return showMainWindow()
  try {
    const u = new URL(url)
    if (u.hostname === 'open') showMainWindow(u.pathname.replace(/^\//, ''))
    else showMainWindow()
  } catch {
    showMainWindow()
  }
}
