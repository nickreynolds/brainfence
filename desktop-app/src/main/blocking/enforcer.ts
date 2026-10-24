// Watches the foreground window and enforces the current blocking state:
// a blocked app is minimized and covered by the overlay — the counterpart of
// Android's BrainfenceAccessibilityService.

import { log } from '../debugLog'
import { prettyName } from './apps'
import { hideOverlay, overlayContext, overlayVisible, showOverlay } from './overlay'
import { foregroundProcess, minimizeWindow, minimizeWindowsOf, type WindowProcess } from './win32'

const POLL_MS = 400

let blocked = new Set<string>()
let names = new Map<string, string>()
let foreground: WindowProcess | null = null
const foregroundListeners = new Set<(exe: string | null) => void>()
let timer: NodeJS.Timeout | null = null

/** Lowercased exe of the current foreground app (null when unknown). */
export function foregroundExe(): string | null {
  return foreground?.exe ?? null
}

export function onForegroundChange(listener: (exe: string | null) => void): () => void {
  foregroundListeners.add(listener)
  return () => foregroundListeners.delete(listener)
}

export function startEnforcer(): void {
  if (timer) return
  timer = setInterval(tick, POLL_MS)
}

export function stopEnforcer(): void {
  if (timer) clearInterval(timer)
  timer = null
}

/** New blocking state from the evaluator. [appNames] maps exe → display name. */
export function setBlockedApps(exes: string[], appNames: Map<string, string>): void {
  const next = new Set(exes)
  const newlyBlocked = [...next].filter((e) => !blocked.has(e))
  blocked = next
  names = appNames
  // Lifted while the overlay is up: take it down.
  const ctx = overlayContext()
  if (ctx && !blocked.has(ctx.exe)) hideOverlay()
  // Newly blocked apps that are already open get minimized right away.
  if (newlyBlocked.length > 0) {
    const n = minimizeWindowsOf(new Set(newlyBlocked))
    if (n > 0) log('blocking', `Minimized ${n} window(s) of newly blocked apps`, newlyBlocked.join(', '))
  }
  tick()
}

function tick(): void {
  let fg: WindowProcess | null
  try {
    fg = foregroundProcess()
  } catch {
    return
  }
  const prevExe = foreground?.exe ?? null
  foreground = fg
  const exe = fg?.exe ?? null
  if (exe !== prevExe) foregroundListeners.forEach((l) => l(exe))

  // Our own windows (main window, the overlay itself) never count.
  if (!fg || fg.pid === process.pid) return

  if (exe && blocked.has(exe)) {
    const name = names.get(exe) ?? prettyName(exe)
    if (!overlayVisible() || overlayContext()?.exe !== exe) log('blocking', `Blocked app opened: ${exe}`)
    minimizeWindow(fg.hwnd)
    showOverlay(exe, name)
  } else if (overlayVisible() && exe !== prevExe) {
    // The user switched to an allowed app: get out of the way.
    hideOverlay()
  }
}
