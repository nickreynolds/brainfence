import type { BlockingRule } from '@shared/models'

function windowsRef(rules: BlockingRule[], exe: string) {
  for (const r of rules) {
    const ref = r.blockedAppRefs.find((a) => a.platform === 'windows' && String(a.exe).toLowerCase() === exe)
    if (ref) return ref
  }
  return null
}

export function prettyExe(exe: string): string {
  const stem = exe.replace(/\.exe$/i, '')
  return stem.charAt(0).toUpperCase() + stem.slice(1)
}

/** Display name for a blocked exe, from the rule entry's saved name. */
export function appLabel(rules: BlockingRule[], exe: string): string {
  const ref = windowsRef(rules, exe)
  return ref?.name ? String(ref.name) : prettyExe(exe)
}

export function appPath(rules: BlockingRule[], exe: string): string | null {
  const ref = windowsRef(rules, exe)
  return ref?.path ? String(ref.path) : null
}
