import type { Report, ReportSubmission, Task } from '@shared/models'
import { REPORT_DEFINITION_TABLES, REPORT_SUBMISSION_TABLES, getReport, getTodaysTaskSubmission, submitReport } from '@shared/repos/reports'
import { completeTask } from '@shared/repos/tasks'
import { reportIdFromConfig } from '@shared/verification'
import { ReportForm, ReportSubmissionSummary } from '../../components/ReportForm'
import { Loading } from '../../components/ui'
import { db, useAction, useNow, useQuery } from '../../lib/hooks'
import { useRouter } from '../../lib/router'
import { NotYetAvailable, TaskTopBar, isBeforeStart } from './common'

/** Port of ReportTaskScreen. */
export function ReportTaskScreen({ task }: { task: Task }) {
  const { back } = useRouter()
  const now = useNow(30_000)
  const reportId = reportIdFromConfig(task.verificationConfig)
  const { data: report, loading } = useQuery(() => (reportId ? getReport(db, reportId) : Promise.resolve(null)), REPORT_DEFINITION_TABLES, [reportId], null as Report | null)
  const { data: today } = useQuery(() => getTodaysTaskSubmission(db, task.id), REPORT_SUBMISSION_TABLES, [task.id], null as ReportSubmission | null)
  const { busy, error, run } = useAction()

  const submit = (answers: Record<string, string>, note: string) =>
    void run(async () => {
      if (!report) return
      const submissionId = await submitReport(db, report, answers, note, task.id, null)
      await completeTask(db, task.id, JSON.stringify({ method: 'report', submission_id: submissionId }))
      back()
    })

  return (
    <>
      <TaskTopBar task={task} />
      {task.completedToday && today ? (
        <div className="card stack">
          <div className="title-md">Reported today</div>
          <ReportSubmissionSummary submission={today} />
        </div>
      ) : loading ? (
        <Loading />
      ) : !report ? (
        <div className="empty">This task's report can't be found. Edit the task to choose one.</div>
      ) : isBeforeStart(task, now) ? (
        <NotYetAvailable task={task} />
      ) : (
        <div className="stack">
          <ReportForm report={report} isSubmitting={busy} onSubmit={submit} />
          {error && <div className="err-text small">{error}</div>}
        </div>
      )}
    </>
  )
}
