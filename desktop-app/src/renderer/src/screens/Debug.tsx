import { useEffect, useState } from 'react'
import type { DebugLogEntry } from '@shared/ipc'
import { Chip, IconButton, TopBar } from '../components/ui'
import { useEnforcement } from '../lib/hooks'

const CATEGORIES = ['blocking', 'website', 'service', 'config_lock', 'duration', 'meditation', 'companion', 'geofence', 'location', 'contemplation']

/** Port of DebugScreen, plus a live view of the enforcement state. */
export function DebugScreen() {
  const [category, setCategory] = useState<string | null>(null)
  const [logs, setLogs] = useState<DebugLogEntry[]>([])
  const enforcement = useEnforcement()

  const load = () => void window.api.debug.getLogs(category).then(setLogs)
  useEffect(() => {
    load()
    const id = setInterval(load, 3000)
    return () => clearInterval(id)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [category])

  return (
    <>
      <TopBar title="Debug Logs" back={false} actions={<IconButton icon="delete" title="Clear logs" onClick={() => void window.api.debug.clear().then(load)} />} />
      <div className="stack">
        {enforcement && (
          <div className="card mono stack sm">
            <div>foreground: {enforcement.foregroundExe ?? '—'}</div>
            <div>home presence: {enforcement.homePresence}</div>
            <div>blocked apps: {enforcement.state.blockedApps.join(', ') || '—'}</div>
            <div>blocked domains: {enforcement.state.blockedDomains.join(', ') || '—'}</div>
            <div>hosts writable: {String(enforcement.website.writable)}</div>
            <div>evaluated: {new Date(enforcement.evaluatedAt).toLocaleTimeString()}</div>
            <div><button className="btn tonal sm" onClick={() => void window.api.debug.previewOverlay()}>Preview blocking screen</button></div>
          </div>
        )}
        <div className="chips">
          <Chip selected={category == null} onClick={() => setCategory(null)}>All</Chip>
          {CATEGORIES.map((c) => <Chip key={c} selected={category === c} onClick={() => setCategory(c)}>{c}</Chip>)}
        </div>
        {logs.length === 0 && <div className="empty">No logs</div>}
        {logs.map((l) => (
          <div key={l.id} className="mono" style={{ borderBottom: '1px solid var(--outline-variant)', padding: '6px 0' }}>
            <span className="muted">{new Date(l.timestamp).toLocaleString()}</span> <b>[{l.category}]</b> {l.message}
            {l.data && <div className="muted" style={{ wordBreak: 'break-all' }}>{l.data}</div>}
          </div>
        ))}
      </div>
    </>
  )
}
