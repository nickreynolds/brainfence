import { app, dialog, Menu, nativeImage, Tray } from 'electron'
import type { EnforcementInfo } from '@shared/ipc'
import { resourcePath, setQuitting, showMainWindow } from './windows'

let tray: Tray | null = null
let lastSummary = ''

export function createTray(): void {
  const image = nativeImage.createFromPath(resourcePath('tray.png'))
  tray = new Tray(image.isEmpty() ? nativeImage.createFromPath(resourcePath('icon.png')).resize({ width: 16, height: 16 }) : image)
  tray.setToolTip('Brainfence')
  tray.on('click', () => showMainWindow())
  updateTray(null)
}

export function updateTray(info: EnforcementInfo | null): void {
  if (!tray) return
  const apps = info?.state.blockedApps.length ?? 0
  const domains = info?.state.blockedDomains.length ?? 0
  const summary = apps + domains === 0
    ? 'All clear — nothing blocked'
    : [apps ? `${apps} app${apps === 1 ? '' : 's'}` : '', domains ? `${domains} site${domains === 1 ? '' : 's'}` : ''].filter(Boolean).join(' and ') + ' blocked'
  if (summary === lastSummary) return
  lastSummary = summary
  tray.setToolTip(`Brainfence — ${summary}`)
  tray.setContextMenu(
    Menu.buildFromTemplate([
      { label: summary, enabled: false },
      { type: 'separator' },
      { label: 'Open Brainfence', click: () => showMainWindow() },
      { label: 'Blocking rules', click: () => showMainWindow('blocking') },
      { type: 'separator' },
      { label: 'Quit Brainfence…', click: () => void confirmQuit() },
    ]),
  )
}

/**
 * Quitting stops enforcement, so it takes a deliberate confirmation
 * (high friction, not a lock: the app can still be ended from Task Manager).
 */
export async function confirmQuit(): Promise<void> {
  const { response } = await dialog.showMessageBox({
    type: 'warning',
    title: 'Quit Brainfence?',
    message: 'Quit Brainfence?',
    detail: 'App blocking and task timers stop until Brainfence runs again. Blocked websites stay blocked until it next updates them.',
    buttons: ['Keep running', 'Quit'],
    defaultId: 0,
    cancelId: 0,
  })
  if (response === 1) {
    setQuitting()
    app.quit()
  }
}
