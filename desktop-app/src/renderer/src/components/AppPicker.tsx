import { useEffect, useMemo, useState } from 'react'
import type { InstalledApp } from '@shared/ipc'
import { Icon } from './Icon'
import { AppIcon, Chip, TextInput } from './ui'

export interface PickedApp {
  exe: string
  name: string
  path: string | null
}

let cache: Promise<InstalledApp[]> | null = null
function loadApps(): Promise<InstalledApp[]> {
  cache ??= window.api.apps.list().catch(() => [])
  return cache
}

/**
 * Searchable picker of Windows apps (Start Menu + open windows), with manual
 * exe entry for anything not listed — the counterpart of Android's app picker.
 */
export function AppPicker({ selected, onToggle }: { selected: PickedApp[]; onToggle: (app: PickedApp) => void }) {
  const [apps, setApps] = useState<InstalledApp[] | null>(null)
  const [query, setQuery] = useState('')
  const [manual, setManual] = useState('')

  useEffect(() => {
    let alive = true
    void loadApps().then((a) => alive && setApps(a))
    return () => {
      alive = false
    }
  }, [])

  const selectedSet = new Set(selected.map((a) => a.exe))
  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase()
    return (apps ?? []).filter((a) => !q || a.name.toLowerCase().includes(q) || a.exe.includes(q)).slice(0, 80)
  }, [apps, query])

  const addManual = () => {
    let exe = manual.trim().toLowerCase().split(/[\\/]/).pop() ?? ''
    if (!exe) return
    if (!exe.endsWith('.exe')) exe += '.exe'
    if (!selectedSet.has(exe)) onToggle({ exe, name: exe.replace(/\.exe$/, ''), path: null })
    setManual('')
  }

  return (
    <div className="stack sm">
      {selected.length > 0 && (
        <div className="chips">
          {selected.map((a) => (
            <Chip key={a.exe} selected onClick={() => onToggle(a)}>
              <AppIcon path={a.path} /> {a.name} <Icon name="close" size="sm" />
            </Chip>
          ))}
        </div>
      )}
      <TextInput value={query} onChange={setQuery} placeholder="Search apps" />
      <div className="card outlined" style={{ maxHeight: 280, overflow: 'auto', padding: 4 }}>
        {apps == null ? (
          <div className="empty small">Finding installed apps…</div>
        ) : filtered.length === 0 ? (
          <div className="empty small">No matching apps — add it by file name below.</div>
        ) : (
          filtered.map((a) => (
            <label key={a.exe} className="list-item clickable" style={{ minHeight: 40, padding: '6px 10px' }}>
              <input type="checkbox" checked={selectedSet.has(a.exe)} onChange={() => onToggle({ exe: a.exe, name: a.name, path: a.path })} />
              <AppIcon path={a.path} />
              <div className="grow">
                <div>{a.name}</div>
                <div className="small muted">{a.exe}{a.running ? ' · running' : ''}</div>
              </div>
            </label>
          ))
        )}
      </div>
      <div className="row">
        <div className="grow"><TextInput value={manual} onChange={setManual} placeholder="Or type an executable, e.g. steam.exe" onEnter={addManual} /></div>
        <button className="btn tonal sm" onClick={addManual} disabled={!manual.trim()}>Add</button>
      </div>
    </div>
  )
}
