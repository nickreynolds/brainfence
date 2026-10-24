// The app picker's source: Start Menu shortcuts plus currently open windows —
// the Windows counterpart of Android's InstalledAppsProvider.

import { app } from 'electron'
import { basename } from 'node:path'
import type { InstalledApp } from '@shared/ipc'
import { runPowerShell } from '../powershell'
import { listAppWindows } from './win32'

const START_MENU_SCRIPT = `
$ErrorActionPreference = 'SilentlyContinue'
$shell = New-Object -ComObject WScript.Shell
$dirs = @("$env:ProgramData\\Microsoft\\Windows\\Start Menu\\Programs", "$env:APPDATA\\Microsoft\\Windows\\Start Menu\\Programs")
$out = foreach ($d in $dirs) {
  Get-ChildItem -Path $d -Filter *.lnk -Recurse | ForEach-Object {
    $t = $shell.CreateShortcut($_.FullName).TargetPath
    if ($t -and $t.ToLower().EndsWith('.exe')) { [pscustomobject]@{ name = $_.BaseName; path = $t } }
  }
}
@($out) | ConvertTo-Json -Compress
`

const SKIP = /uninstall|unins\d*\.exe|setup|update|readme|help|documentation|release notes/i
// Never offer to block the shell or Brainfence itself.
const NEVER_BLOCK = new Set([
  'explorer.exe', 'applicationframehost.exe', 'searchhost.exe', 'shellexperiencehost.exe', 'startmenuexperiencehost.exe',
  'lockapp.exe', 'logonui.exe', 'taskmgr.exe', 'systemsettings.exe', 'textinputhost.exe', 'searchapp.exe', 'brainfence.exe',
])

let cached: { at: number; apps: Omit<InstalledApp, 'running'>[] } | null = null

async function startMenuApps(): Promise<Omit<InstalledApp, 'running'>[]> {
  if (cached && Date.now() - cached.at < 10 * 60_000) return cached.apps
  let parsed: { name: string; path: string }[] = []
  try {
    const json = (await runPowerShell(START_MENU_SCRIPT, 60_000)).trim()
    if (json) {
      const value = JSON.parse(json)
      parsed = Array.isArray(value) ? value : [value]
    }
  } catch (e) {
    console.warn('Start Menu scan failed', e)
  }
  const apps = parsed
    .filter((a) => a?.path && !SKIP.test(a.name) && !SKIP.test(basename(a.path)))
    .map((a) => ({ exe: basename(a.path).toLowerCase(), name: a.name, path: a.path }))
  cached = { at: Date.now(), apps }
  return apps
}

export async function listInstalledApps(): Promise<InstalledApp[]> {
  const self = basename(process.execPath).toLowerCase()
  const byExe = new Map<string, InstalledApp>()
  for (const a of await startMenuApps()) {
    if (!byExe.has(a.exe)) byExe.set(a.exe, { ...a, running: false })
  }
  for (const w of listAppWindows()) {
    if (!w.exe) continue
    const existing = byExe.get(w.exe)
    if (existing) existing.running = true
    else byExe.set(w.exe, { exe: w.exe, name: prettyName(w.exe), path: w.path, running: true })
  }
  return [...byExe.values()]
    .filter((a) => a.exe !== self && !NEVER_BLOCK.has(a.exe))
    .sort((a, b) => Number(b.running) - Number(a.running) || a.name.localeCompare(b.name))
}

export function prettyName(exe: string): string {
  const stem = exe.replace(/\.exe$/i, '')
  return stem.charAt(0).toUpperCase() + stem.slice(1)
}

const iconCache = new Map<string, string | null>()

export async function appIcon(path: string): Promise<string | null> {
  if (iconCache.has(path)) return iconCache.get(path)!
  let url: string | null = null
  try {
    url = (await app.getFileIcon(path, { size: 'normal' })).toDataURL()
  } catch {
    url = null
  }
  iconCache.set(path, url)
  return url
}
