import { runPowerShell, psQuote } from './powershell'
import { resourcePath } from './windows'

/** Play the meditation bell from the main process (works with no window open). */
export function playChime(): void {
  const path = resourcePath('meditation_bell.wav')
  runPowerShell(`(New-Object Media.SoundPlayer ${psQuote(path)}).PlaySync()`, 20_000).catch(() => undefined)
}
