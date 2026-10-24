// Website blocking through the Windows hosts file: blocked domains resolve to
// 0.0.0.0 in every browser and app. The file is admin-only by default, so a
// one-time elevated step grants the current user modify rights on it; after
// that Brainfence rewrites only its own marked section, without prompting.

import { execFile } from 'node:child_process'
import { closeSync, openSync, readFileSync, writeFileSync } from 'node:fs'
import { userInfo } from 'node:os'
import type { WebsiteBlockingStatus } from '@shared/ipc'
import { log } from '../debugLog'
import { psQuote, runPowerShell } from '../powershell'

export const HOSTS_PATH = `${process.env.SystemRoot ?? 'C:\\Windows'}\\System32\\drivers\\etc\\hosts`
const BEGIN = '# BEGIN BRAINFENCE (managed by Brainfence - do not edit)'
const END = '# END BRAINFENCE'
const DOMAINS_TAG = '# domains:'
/** hosts has no wildcards: cover the usual web and mobile hostnames too. */
const PREFIXES = ['', 'www.', 'm.', 'mobile.']

let active: string[] = []
let lastError: string | null = null

export function isHostsWritable(): boolean {
  try {
    closeSync(openSync(HOSTS_PATH, 'r+'))
    return true
  } catch {
    return false
  }
}

export function websiteStatus(): WebsiteBlockingStatus {
  return { writable: isHostsWritable(), activeDomains: active, error: lastError }
}

/** Rewrite Brainfence's hosts section to block exactly [domains]. No-op if unchanged. */
export function applyBlockedDomains(domains: string[]): void {
  const wanted = [...new Set(domains)].sort()
  let current: string
  try {
    current = readFileSync(HOSTS_PATH, 'utf8')
  } catch (e: any) {
    lastError = `Can't read hosts file: ${e.message}`
    return
  }
  const { outside, inside } = splitSection(current)
  if (sameList(inside, wanted)) {
    active = wanted
    return
  }
  const lines = wanted.flatMap((d) => PREFIXES.flatMap((p) => [`0.0.0.0 ${p}${d}`, `:: ${p}${d}`]))
  const section = wanted.length ? `${BEGIN}\r\n${DOMAINS_TAG} ${wanted.join(' ')}\r\n${lines.join('\r\n')}\r\n${END}\r\n` : ''
  const next = outside.replace(/(\r?\n)*$/, '\r\n') + section
  try {
    writeFileSync(HOSTS_PATH, next, 'utf8')
    active = wanted
    lastError = null
    log('website', `Hosts file updated: ${wanted.length} domain(s) blocked`, wanted.join(', '))
    flushDns()
  } catch (e: any) {
    lastError = e.code === 'EPERM' || e.code === 'EACCES' ? 'Website blocking needs one-time setup' : `Can't write hosts file: ${e.message}`
  }
}

function splitSection(content: string): { outside: string; inside: string[] } {
  const start = content.indexOf(BEGIN)
  if (start < 0) return { outside: content, inside: [] }
  const endIdx = content.indexOf(END, start)
  const stop = endIdx < 0 ? content.length : endIdx + END.length
  const body = content.slice(start + BEGIN.length, endIdx < 0 ? content.length : endIdx)
  const tagLine = body.split(/\r?\n/).find((l) => l.trim().startsWith(DOMAINS_TAG))
  const inside = tagLine ? tagLine.trim().slice(DOMAINS_TAG.length).trim().split(/\s+/).filter(Boolean).sort() : []
  return { outside: content.slice(0, start) + content.slice(stop).replace(/^\r?\n/, ''), inside }
}

function sameList(a: string[], b: string[]): boolean {
  return a.length === b.length && a.every((v, i) => v === b[i])
}

function flushDns(): void {
  execFile('ipconfig', ['/flushdns'], { windowsHide: true }, () => undefined)
}

/**
 * One-time elevated setup (UAC prompt): grant the current user modify rights
 * on the hosts file and clear its read-only flag.
 */
export async function setupHostsAccess(): Promise<WebsiteBlockingStatus> {
  const user = process.env.USERDOMAIN ? `${process.env.USERDOMAIN}\\${userInfo().username}` : userInfo().username
  const inner = `attrib -r ${psQuote(HOSTS_PATH)}; icacls ${psQuote(HOSTS_PATH)} /grant ${psQuote(`${user}:(M)`)}`
  const encoded = Buffer.from(inner, 'utf16le').toString('base64')
  const script = `
$p = Start-Process -FilePath powershell.exe -Verb RunAs -WindowStyle Hidden -Wait -PassThru -ArgumentList '-NoProfile','-ExecutionPolicy','Bypass','-EncodedCommand','${encoded}'
exit $p.ExitCode`
  try {
    await runPowerShell(script, 120_000)
    lastError = null
    log('website', 'Hosts file access granted')
  } catch (e: any) {
    lastError = /canceled|cancelled/i.test(e.message) ? 'Setup was cancelled at the Windows prompt' : `Setup failed: ${e.message}`
  }
  return websiteStatus()
}
