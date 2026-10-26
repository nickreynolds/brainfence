import { createReport } from '@shared/repos/reports'
import { Icon } from '../components/Icon'
import { TopBar } from '../components/ui'
import { db } from '../lib/hooks'
import { useRouter } from '../lib/router'
import { JournalHistoryList } from './JournalHistory'
import { ReportsList } from './Reports'

/** Review's tabs. Journal comes first; more kinds of entries will join it. */
export type ReviewTab = 'journal' | 'reports'
const TABS: { tab: ReviewTab; label: string }[] = [
  { tab: 'journal', label: 'Journal' },
  { tab: 'reports', label: 'Reports' },
]

/**
 * The Review section: looking back over journal entries and reports. Port of
 * Android's ReviewScreen. Each tab is its own route (`review`, `review/reports`)
 * so Back from a report editor returns to the Reports tab.
 */
export function ReviewScreen({ tab }: { tab: ReviewTab }) {
  const { navigate } = useRouter()
  const newReport = () => void createReport(db, 'New report').then((id) => navigate(`reports/${id}`))
  return (
    <>
      <TopBar
        title="Review"
        back={false}
        actions={tab === 'reports' ? <button className="btn create" onClick={newReport}><Icon name="add" /> New report</button> : undefined}
      />
      <div className="tabs">
        {TABS.map((t) => (
          <button
            key={t.tab}
            className={`tab ${tab === t.tab ? 'active' : ''}`}
            onClick={() => navigate(t.tab === 'journal' ? 'review' : `review/${t.tab}`, { replace: true })}
          >
            {t.label}
          </button>
        ))}
      </div>
      {tab === 'journal' ? <JournalHistoryList /> : <ReportsList />}
    </>
  )
}
