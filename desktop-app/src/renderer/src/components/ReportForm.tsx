import { useState } from 'react'
import type { Report, ReportSubmission } from '@shared/models'
import { unansweredMetrics } from '@shared/reports'
import { Chip, TextInput } from './ui'

/** Port of ReportForm: option chips per metric, an optional note, submit when complete. */
export function ReportForm({ report, isSubmitting, onSubmit }: { report: Report; isSubmitting: boolean; onSubmit: (answers: Record<string, string>, note: string) => void }) {
  const [answers, setAnswers] = useState<Record<string, string>>({})
  const [note, setNote] = useState('')
  const complete = unansweredMetrics(report, answers).length === 0
  return (
    <div className="stack lg">
      {report.metrics.length === 0 && <div className="muted">This report has no metrics yet. Add some under Reports.</div>}
      {report.metrics.map((metric) => (
        <div key={metric.id} className="stack sm">
          <div className="title-sm">{metric.name}</div>
          {metric.description && <div className="small muted">{metric.description}</div>}
          <div className="chips">
            {metric.options.map((o) => (
              <Chip key={o.id} selected={answers[metric.id] === o.id} disabled={isSubmitting} onClick={() => setAnswers({ ...answers, [metric.id]: o.id })}>
                {o.label}
              </Chip>
            ))}
          </div>
        </div>
      ))}
      {report.includeNote && <TextInput value={note} onChange={setNote} multiline rows={2} placeholder="Note (optional)" disabled={isSubmitting} />}
      <button className="btn block" disabled={!complete || report.metrics.length === 0 || isSubmitting} onClick={() => onSubmit(answers, note)}>
        {isSubmitting ? 'Saving…' : 'Submit'}
      </button>
    </div>
  )
}

export function ReportSubmissionSummary({ submission }: { submission: ReportSubmission }) {
  return (
    <div className="stack sm">
      {submission.entries.map((e, i) => (
        <div key={i}>
          <span className="muted">{e.metricName}: </span>
          <span style={{ fontWeight: 600 }}>{e.optionLabel}</span>
        </div>
      ))}
      {submission.note && <div className="small muted">“{submission.note}”</div>}
    </div>
  )
}
