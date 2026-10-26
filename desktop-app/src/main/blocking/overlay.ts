// The blocking overlay: a full-screen, always-on-top window shown over a
// blocked app — the Windows counterpart of Android's BlockingActivity.

import { BrowserWindow, screen } from 'electron'
import { loadRenderer, preloadPath, surfaceColor } from '../windows'

let win: BrowserWindow | null = null
let context: { exe: string; name: string } | null = null

export function overlayContext(): { exe: string; name: string } | null {
  return context
}

export function overlayVisible(): boolean {
  return !!win && !win.isDestroyed() && win.isVisible()
}

export function overlayWebContentsId(): number | null {
  return win && !win.isDestroyed() ? win.webContents.id : null
}

function ensureWindow(): BrowserWindow {
  if (win && !win.isDestroyed()) return win
  win = new BrowserWindow({
    show: false,
    frame: false,
    resizable: false,
    movable: false,
    minimizable: false,
    maximizable: false,
    skipTaskbar: true,
    alwaysOnTop: true,
    backgroundColor: surfaceColor(),
    webPreferences: { preload: preloadPath(), contextIsolation: true, sandbox: false },
  })
  win.setAlwaysOnTop(true, 'screen-saver')
  // Closing (Alt+F4) just hides it; the app stays minimized and blocked.
  win.on('close', (e) => {
    e.preventDefault()
    hideOverlay()
  })
  loadRenderer(win, 'overlay')
  return win
}

export function showOverlay(exe: string, name: string): void {
  const w = ensureWindow()
  const changed = context?.exe !== exe
  context = { exe, name }
  const display = screen.getDisplayNearestPoint(screen.getCursorScreenPoint())
  w.setBounds(display.bounds)
  w.setBackgroundColor(surfaceColor())
  if (changed || !w.isVisible()) w.webContents.send('overlay:context', context)
  if (!w.isVisible()) w.show()
  w.moveTop()
  w.focus()
}

export function hideOverlay(): void {
  if (win && !win.isDestroyed() && win.isVisible()) win.hide()
}

export function destroyOverlay(): void {
  if (win && !win.isDestroyed()) {
    win.removeAllListeners('close')
    win.destroy()
  }
  win = null
}
