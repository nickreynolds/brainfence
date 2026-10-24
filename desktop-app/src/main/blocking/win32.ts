// Thin Win32 bindings via koffi (prebuilt FFI — no native compilation needed).

import koffi from 'koffi'
import { basename } from 'node:path'

const user32 = koffi.load('user32.dll')
const kernel32 = koffi.load('kernel32.dll')

// Handles are declared as intptr so they come back as plain numbers/bigints.
const GetForegroundWindow = user32.func('intptr __stdcall GetForegroundWindow()')
const GetWindowThreadProcessId = user32.func('uint32 __stdcall GetWindowThreadProcessId(intptr hWnd, _Out_ uint32 *lpdwProcessId)')
const ShowWindow = user32.func('bool __stdcall ShowWindow(intptr hWnd, int nCmdShow)')
const IsWindowVisible = user32.func('bool __stdcall IsWindowVisible(intptr hWnd)')
const IsIconic = user32.func('bool __stdcall IsIconic(intptr hWnd)')
const GetWindowTextW = user32.func('int __stdcall GetWindowTextW(intptr hWnd, _Out_ char16_t *lpString, int nMaxCount)')
const GetWindowLongPtrW = user32.func('intptr __stdcall GetWindowLongPtrW(intptr hWnd, int nIndex)')
const GetWindow = user32.func('intptr __stdcall GetWindow(intptr hWnd, uint32 uCmd)')
const EnumWindowsProc = koffi.proto('bool __stdcall EnumWindowsProc(intptr hwnd, intptr lParam)')
const EnumWindows = user32.func('bool __stdcall EnumWindows(EnumWindowsProc *lpEnumFunc, intptr lParam)')
const EnumChildWindows = user32.func('bool __stdcall EnumChildWindows(intptr hWndParent, EnumWindowsProc *lpEnumFunc, intptr lParam)')

const OpenProcess = kernel32.func('intptr __stdcall OpenProcess(uint32 dwDesiredAccess, bool bInheritHandle, uint32 dwProcessId)')
const CloseHandle = kernel32.func('bool __stdcall CloseHandle(intptr hObject)')
const QueryFullProcessImageNameW = kernel32.func(
  'bool __stdcall QueryFullProcessImageNameW(intptr hProcess, uint32 dwFlags, _Out_ char16_t *lpExeName, _Inout_ uint32 *lpdwSize)',
)

const PROCESS_QUERY_LIMITED_INFORMATION = 0x1000
const SW_MINIMIZE = 6
const GWL_EXSTYLE = -20
const WS_EX_TOOLWINDOW = 0x80
const GW_OWNER = 4

export type Hwnd = number | bigint

export interface WindowProcess {
  hwnd: Hwnd
  pid: number
  /** Full path of the executable, or null if the process can't be queried (e.g. elevated). */
  path: string | null
  /** Lowercased exe file name, e.g. "discord.exe". */
  exe: string | null
}

const isNull = (h: Hwnd) => h === 0 || h === 0n

export function processPath(pid: number): string | null {
  if (!pid) return null
  const handle = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, false, pid)
  if (isNull(handle)) return null
  try {
    const buf = Buffer.alloc(32_768 * 2)
    const size = [32_768]
    if (!QueryFullProcessImageNameW(handle, 0, buf, size)) return null
    return buf.toString('utf16le', 0, size[0] * 2)
  } finally {
    CloseHandle(handle)
  }
}

export function windowPid(hwnd: Hwnd): number {
  const pid = [0]
  GetWindowThreadProcessId(hwnd, pid)
  return pid[0]
}

function describe(hwnd: Hwnd, pid: number): WindowProcess {
  const path = processPath(pid)
  return { hwnd, pid, path, exe: path ? basename(path).toLowerCase() : null }
}

/**
 * The process behind the foreground window. UWP apps are hosted by
 * ApplicationFrameHost.exe; the real app is a child window in another process,
 * so look through the children for it.
 */
export function foregroundProcess(): WindowProcess | null {
  const hwnd = GetForegroundWindow()
  if (isNull(hwnd)) return null
  const info = describe(hwnd, windowPid(hwnd))
  if (info.exe === 'applicationframehost.exe') {
    let child: WindowProcess | null = null
    EnumChildWindows(hwnd, (h: Hwnd) => {
      const pid = windowPid(h)
      if (pid && pid !== info.pid) {
        child = { ...describe(h, pid), hwnd }
        return false
      }
      return true
    }, 0)
    if (child) return child
  }
  return info
}

export function minimizeWindow(hwnd: Hwnd): void {
  if (!IsIconic(hwnd)) ShowWindow(hwnd, SW_MINIMIZE)
}

export function windowTitle(hwnd: Hwnd): string {
  const buf = Buffer.alloc(512 * 2)
  const n = GetWindowTextW(hwnd, buf, 512)
  return n > 0 ? buf.toString('utf16le', 0, n * 2) : ''
}

/** Top-level, visible, titled, non-tool windows — what Alt+Tab would show. */
export function listAppWindows(): WindowProcess[] {
  const out: WindowProcess[] = []
  const seen = new Set<number>()
  EnumWindows((hwnd: Hwnd) => {
    try {
      if (!IsWindowVisible(hwnd)) return true
      if (!isNull(GetWindow(hwnd, GW_OWNER))) return true
      if (Number(GetWindowLongPtrW(hwnd, GWL_EXSTYLE)) & WS_EX_TOOLWINDOW) return true
      if (windowTitle(hwnd) === '') return true
      const pid = windowPid(hwnd)
      if (seen.has(pid)) return true
      seen.add(pid)
      const info = describe(hwnd, pid)
      if (info.exe) out.push(info)
    } catch {
      // Skip windows we can't inspect.
    }
    return true
  }, 0)
  return out
}

/** Minimize every top-level window belonging to one of [exes] (lowercased names). */
export function minimizeWindowsOf(exes: Set<string>): number {
  let count = 0
  const pidExe = new Map<number, string | null>()
  EnumWindows((hwnd: Hwnd) => {
    try {
      if (!IsWindowVisible(hwnd) || IsIconic(hwnd)) return true
      const pid = windowPid(hwnd)
      if (!pidExe.has(pid)) pidExe.set(pid, describe(hwnd, pid).exe)
      const exe = pidExe.get(pid)
      if (exe && exes.has(exe) && windowTitle(hwnd) !== '') {
        ShowWindow(hwnd, SW_MINIMIZE)
        count++
      }
    } catch {
      // ignore
    }
    return true
  }, 0)
  return count
}

void EnumWindowsProc
