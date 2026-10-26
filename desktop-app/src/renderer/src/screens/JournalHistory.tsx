import type { JournalEntry } from '@shared/models'
import { getJournalEntries } from '@shared/repos/shopping'
import { TASK_TABLES } from '@shared/repos/tasks'
import { formatDateTime } from '../lib/format'
import { db, useQuery } from '../lib/hooks'

/** Journal entries, newest first: Review's Journal tab. Port of Android's JournalHistoryList. */
export function JournalHistoryList() {
  const { data: entries, loading } = useQuery(() => getJournalEntries(db), TASK_TABLES, [], [] as JournalEntry[])
  return (
    <>
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
