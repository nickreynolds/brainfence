import { app, BrowserWindow, shell } from 'electron'
import { join } from 'node:path'

let mainWindow: BrowserWindow | null = null
let quitting = false

export const preloadPath = () => join(__dirname, '../preload/index.js')
export const resourcePath = (name: string) =>
  app.isPackaged ? join(process.resourcesPath, name) : join(__dirname, '../../resources', name)

/** Load the renderer at a hash route ("" = the main app, "overlay" = the blocking overlay). */
export function loadRenderer(win: BrowserWindow, route = ''): void {
  const devUrl = process.env.ELECTRON_RENDERER_URL
  if (!app.isPackaged && devUrl) void win.loadURL(`${devUrl}#/${route}`)
  else void win.loadFile(join(__dirname, '../renderer/index.html'), { hash: `/${route}` })
}

export function setQuitting(): void {
  quitting = true
}

export function createMainWindow(showOnCreate: boolean): BrowserWindow {
  mainWindow = new BrowserWindow({
    width: 1100,
    height: 780,
    minWidth: 420,
    minHeight: 520,
    show: false,
    title: 'Brainfence',
    icon: resourcePath('icon.png'),
    backgroundColor: '#fffbfe',
    autoHideMenuBar: true,
    webPreferences: { preload: preloadPath(), contextIsolation: true, sandbox: false },
  })
  mainWindow.setMenuBarVisibility(false)
  mainWindow.once('ready-to-show', () => {
    if (showOnCreate) mainWindow?.show()
  })
  // Closing hides to the tray: blocking keeps running in the background.
  mainWindow.on('close', (e) => {
    if (!quitting) {
      e.preventDefault()
      mainWindow?.hide()
    }
  })
  mainWindow.webContents.setWindowOpenHandler(({ url }) => {
    void shell.openExternal(url)
    return { action: 'deny' }
  })
  loadRenderer(mainWindow)
  return mainWindow
}

export function getMainWindow(): BrowserWindow | null {
  return mainWindow && !mainWindow.isDestroyed() ? mainWindow : null
}

export function showMainWindow(route?: string): void {
  const win = getMainWindow() ?? createMainWindow(true)
  if (win.isMinimized()) win.restore()
  win.show()
  win.focus()
  if (route) win.webContents.send('window:navigate', route)
}

/** Send to every renderer (main window and overlay). */
export function broadcast(channel: string, ...args: unknown[]): void {
  for (const w of BrowserWindow.getAllWindows()) {
    if (!w.isDestroyed()) w.webContents.send(channel, ...args)
  }
}
