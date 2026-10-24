import { execFile } from 'node:child_process'

/**
 * Runs a PowerShell script (passed as -EncodedCommand, so no quoting issues)
 * and resolves with its stdout. Rejects on non-zero exit or timeout.
 */
export function runPowerShell(script: string, timeoutMs = 30_000): Promise<string> {
  const encoded = Buffer.from(script, 'utf16le').toString('base64')
  return new Promise((resolve, reject) => {
    execFile(
      'powershell.exe',
      ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-EncodedCommand', encoded],
      { windowsHide: true, timeout: timeoutMs, maxBuffer: 16 * 1024 * 1024 },
      (error, stdout, stderr) => {
        if (error) reject(new Error(stderr?.trim() || error.message))
        else resolve(stdout)
      },
    )
  })
}

/** Single-quoted PowerShell string literal. */
export function psQuote(value: string): string {
  return `'${value.replace(/'/g, "''")}'`
}
