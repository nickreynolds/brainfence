import type { JournalEntry } from '@shared/models'
import { getJournalEntries } from '@shared/repos/shopping'
import { TASK_TABLES } from '@shared/repos/tasks'
import { TopBar } from '../components/ui'
import { formatDateTime } from '../lib/format'
import { db, useQuery } from '../lib/hooks'

/** Port of JournalHistoryScreen. */
export function JournalHistoryScreen() {
  const { data: entries, loading } = useQuery(() => getJournalEntries(db), TASK_TABLES, [], [] as JournalEntry[])
  return (
    <>
      <TopBar title="Journal" back={false} />
      {!loading && entries.length === 0 ? (
        <div className="empty">No journal entries yet.</div>
      ) : (
        <div className="stack">
          {entries.map((e) => (
            <div key={e.id} className="card stack sm">
              <div className="row between">
                <span className="title-sm">{e.taskTitle}</span>
                <span className="small muted">{formatDateTime(e.completedAt)}</span>
              </div>
              <div style={{ whiteSpace: 'pre-wrap' }}>{e.text || <span className="muted">(empty)</span>}</div>
            </div>
          ))}
        </div>
      )}
    </>
  )
}
