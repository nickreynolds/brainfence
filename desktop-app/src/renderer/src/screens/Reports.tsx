import { useEffect, useState } from 'react'
import type { Metric, MetricKind, Report, ReportSubmission } from '@shared/models'
import {
  OptionDraft, REPORT_DEFINITION_TABLES, REPORT_SUBMISSION_TABLES, addMetricToReport, addReportFromLibrary, createMetric,
  deleteMetric, deleteReport, getMetrics, getRecentSubmissions, getReport, getReports, moveMetricInReport, removeMetricFromReport, saveMetric, updateReport,
} from '@shared/repos/reports'
import { MAX_METRIC_OPTIONS, MIN_METRIC_OPTIONS, NEW_METRIC_OPTIONS, REPORT_LIBRARY, validateMetricDraft } from '@shared/reports'
import { Icon } from '../components/Icon'
import { ReportSubmissionSummary } from '../components/ReportForm'
import { Chip, ConfirmDialog, Dialog, ErrorText, Field, IconButton, Loading, Switch, TextInput, TopBar } from '../components/ui'
import { formatDateTime, plural } from '../lib/format'
import { db, useQuery } from '../lib/hooks'
import { useRouter } from '../lib/router'

/** Port of ReportsScreen, plus a recent-answers history. */
/** Reports, starters and every metric: Review's Reports tab. Review's top bar has New report. */
export function ReportsList() {
  const { navigate } = useRouter()
  const { data: reports } = useQuery(() => getReports(db), REPORT_DEFINITION_TABLES, [], [] as Report[])
  const { data: metrics } = useQuery(() => getMetrics(db), REPORT_DEFINITION_TABLES, [], [] as Metric[])
  const { data: recent } = useQuery(() => getRecentSubmissions(db, 20), REPORT_SUBMISSION_TABLES, [], [] as ReportSubmission[])
  const added = new Set(reports.map((r) => r.libraryKey).filter(Boolean))
  const starters = REPORT_LIBRARY.filter((s) => !added.has(s.key))

  return (
    <>
      <div className="stack lg">
        <div className="small muted">A report is a set of metrics you answer from a Report task or a routine's report step. Metrics are shared, so every report that asks one feeds a single history.</div>
        <div className="stack sm">
          {reports.map((r) => (
            <div key={r.id} className="card clickable" onClick={() => navigate(`reports/${r.id}`)}>
              <div className="title-sm">{r.name}</div>
              <div className="small muted">{r.metrics.length ? r.metrics.map((m) => m.name).join(', ') : 'No metrics yet'}</div>
            </div>
          ))}
          {starters.map((s) => (
            <div key={s.key} className="card outlined row">
              <div className="grow">
                <div className="title-sm">Starter: {s.name}</div>
                <div className="small muted">{s.description}</div>
              </div>
              <button className="btn text sm" onClick={() => void addReportFromLibrary(db, s)}>Add</button>
            </div>
          ))}
        </div>
        {metrics.length > 0 && (
          <div className="stack sm">
            <div className="title-md">All metrics</div>
            {metrics.map((m) => (
              <div key={m.id} className="list-item clickable" style={{ minHeight: 48 }} onClick={() => navigate(`reports/metric/${m.id}`)}>
                <div className="grow">
                  <div>{m.name}</div>
                  <div className="small muted">{m.kind === 'ordinal' ? 'Scale' : 'Categories'}: {m.options.map((o) => o.label).join(' · ')}</div>
                </div>
              </div>
            ))}
          </div>
        )}
        {recent.length > 0 && (
          <div className="stack sm">
            <div className="title-md">Recent answers</div>
            {recent.map((s) => (
              <div key={s.id} className="card stack sm">
                <div className="row between"><span className="title-sm">{s.reportName}</span><span className="small muted">{formatDateTime(s.submittedAt)}</span></div>
                <ReportSubmissionSummary submission={s} />
              </div>
            ))}
          </div>
        )}
      </div>
    </>
  )
}

/** Port of ReportEditorScreen. Metric list changes save immediately; name/note on Save. */
export function ReportEditorScreen({ reportId }: { reportId: string }) {
  const { back, navigate } = useRouter()
  const { data: report, loading } = useQuery(() => getReport(db, reportId), REPORT_DEFINITION_TABLES, [reportId], null as Report | null)
  const { data: metrics } = useQuery(() => getMetrics(db), REPORT_DEFINITION_TABLES, [], [] as Metric[])
  const [form, setForm] = useState<{ name: string; includeNote: boolean } | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [adding, setAdding] = useState(false)
  const [confirmDelete, setConfirmDelete] = useState(false)

  useEffect(() => {
    if (report && !form) setForm({ name: report.name, includeNote: report.includeNote })
  }, [report, form])

  if (loading) return <Loading />
  if (!report || !form) return (<><TopBar title="Report" /><div className="empty">This report no longer exists.</div></>)
  const addable = metrics.filter((m) => !report.metrics.some((x) => x.id === m.id))

  return (
    <>
      <TopBar title={report.name} actions={<IconButton icon="delete" title="Delete report" onClick={() => setConfirmDelete(true)} />} />
      <div className="stack lg">
        <Field label="Name"><TextInput value={form.name} onChange={(name) => setForm({ ...form, name })} /></Field>
        <div className="row"><div className="grow">Ask for an optional note</div><Switch checked={form.includeNote} onChange={(includeNote) => setForm({ ...form, includeNote })} /></div>
        <ErrorText error={error} />
        <button className="btn block" onClick={() => {
          if (!form.name.trim()) return setError('Name is required')
          void updateReport(db, reportId, form.name.trim(), form.includeNote).then(back)
        }}>Save</button>

        <div className="stack sm">
          <div className="title-md">Metrics, in the order they're asked</div>
          <div className="small muted">Changes here save immediately.</div>
          {report.metrics.map((m, i) => (
            <div key={m.id} className="card row">
              <div className="grow clickable" style={{ cursor: 'pointer' }} onClick={() => navigate(`reports/metric/${m.id}`)}>
                <div className="title-sm">{m.name}</div>
                <div className="small muted">{m.options.map((o) => o.label).join(' · ')}</div>
              </div>
              <IconButton icon="up" title="Move up" small disabled={i === 0} onClick={() => void moveMetricInReport(db, reportId, m.id, -1)} />
              <IconButton icon="down" title="Move down" small disabled={i === report.metrics.length - 1} onClick={() => void moveMetricInReport(db, reportId, m.id, 1)} />
              <IconButton icon="close" title="Remove from report" small onClick={() => void removeMetricFromReport(db, reportId, m.id)} />
            </div>
          ))}
          <div><button className="btn outlined" onClick={() => setAdding(true)}><Icon name="add" /> Add metric</button></div>
        </div>
      </div>
      {adding && (
        <Dialog title="Add metric" onClose={() => setAdding(false)} actions={<button className="btn text" onClick={() => setAdding(false)}>Cancel</button>}>
          <div className="stack sm">
            <button className="btn tonal" onClick={() => {
              setAdding(false)
              void (async () => {
                const id = await createMetric(db, 'New metric', null, 'ordinal', NEW_METRIC_OPTIONS)
                await addMetricToReport(db, reportId, id)
                navigate(`reports/metric/${id}`)
              })()
            }}><Icon name="add" /> New metric</button>
            {addable.map((m) => (
              <div key={m.id} className="list-item clickable" style={{ minHeight: 44 }} onClick={() => { setAdding(false); void addMetricToReport(db, reportId, m.id) }}>
                <div className="grow"><div>{m.name}</div><div className="small muted">{m.options.map((o) => o.label).join(' · ')}</div></div>
              </div>
            ))}
          </div>
        </Dialog>
      )}
      {confirmDelete && (
        <ConfirmDialog
          title="Delete report?"
          message="Its metrics are kept (they may be used by other reports) and past answers stay in your history. Tasks or routine steps that ask it will need a new report."
          confirmLabel="Delete"
          cancelLabel="Keep"
          danger
          onCancel={() => setConfirmDelete(false)}
          onConfirm={() => void deleteReport(db, reportId).then(back)}
        />
      )}
    </>
  )
}

/** Port of MetricEditorScreen. Options keep their ids across renames/reorders. */
export function MetricEditorScreen({ metricId }: { metricId: string }) {
  const { back } = useRouter()
  const { data: metrics, loading } = useQuery(() => getMetrics(db), REPORT_DEFINITION_TABLES, [], [] as Metric[])
  const { data: reports } = useQuery(() => getReports(db), REPORT_DEFINITION_TABLES, [], [] as Report[])
  const metric = metrics.find((m) => m.id === metricId)
  const [form, setForm] = useState<{ name: string; description: string; kind: MetricKind; options: OptionDraft[] } | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [confirmDelete, setConfirmDelete] = useState(false)

  useEffect(() => {
    if (metric && !form) setForm({ name: metric.name, description: metric.description ?? '', kind: metric.kind, options: metric.options.map((o) => ({ id: o.id, label: o.label })) })
  }, [metric, form])

  if (loading) return <Loading />
  if (!metric || !form) return (<><TopBar title="Metric" /><div className="empty">This metric no longer exists.</div></>)
  const usedIn = reports.filter((r) => r.metrics.some((m) => m.id === metricId)).map((r) => r.name)
  const setOption = (i: number, label: string) => setForm({ ...form, options: form.options.map((o, j) => (j === i ? { ...o, label } : o)) })
  const moveOption = (i: number, d: number) => {
    const opts = [...form.options]
    const [o] = opts.splice(i, 1)
    opts.splice(i + d, 0, o)
    setForm({ ...form, options: opts })
  }
  const save = async () => {
    const err = validateMetricDraft(form.name, form.options.map((o) => o.label))
    if (err) return setError(err)
    await saveMetric(db, metricId, form.name.trim(), form.description.trim() || null, form.kind, form.options.map((o) => ({ ...o, label: o.label.trim() })))
    back()
  }

  return (
    <>
      <TopBar title={metric.name} actions={<IconButton icon="delete" title="Delete metric" onClick={() => setConfirmDelete(true)} />} />
      <div className="stack lg">
        {usedIn.length > 0 && <div className="small muted">Used in {usedIn.join(', ')}</div>}
        <Field label="Name"><TextInput value={form.name} onChange={(name) => setForm({ ...form, name })} /></Field>
        <Field label="Hint (optional)"><TextInput value={form.description} onChange={(description) => setForm({ ...form, description })} placeholder="e.g. 1 = not at all · 5 = very much" /></Field>
        <div className="section-label">Answers are</div>
        <div className="chips">
          <Chip selected={form.kind === 'ordinal'} onClick={() => setForm({ ...form, kind: 'ordinal' })}>A scale (ordered)</Chip>
          <Chip selected={form.kind === 'categorical'} onClick={() => setForm({ ...form, kind: 'categorical' })}>Categories</Chip>
        </div>
        <div className="section-label">Options ({MIN_METRIC_OPTIONS}–{MAX_METRIC_OPTIONS})</div>
        {form.options.map((o, i) => (
          <div key={o.id ?? `new-${i}`} className="row">
            <div className="grow"><TextInput value={o.label} onChange={(v) => setOption(i, v)} /></div>
            <IconButton icon="up" title="Move up" small disabled={i === 0} onClick={() => moveOption(i, -1)} />
            <IconButton icon="down" title="Move down" small disabled={i === form.options.length - 1} onClick={() => moveOption(i, 1)} />
            <IconButton icon="close" title="Remove option" small disabled={form.options.length <= MIN_METRIC_OPTIONS} onClick={() => setForm({ ...form, options: form.options.filter((_, j) => j !== i) })} />
          </div>
        ))}
        {form.options.length < MAX_METRIC_OPTIONS && (
          <div><button className="btn text" onClick={() => setForm({ ...form, options: [...form.options, { id: null, label: '' }] })}><Icon name="add" size="sm" /> Add option</button></div>
        )}
        <div className="small muted">Renaming or reordering keeps past answers as they were recorded.</div>
        <ErrorText error={error} />
        <button className="btn block" onClick={() => void save()}>Save</button>
      </div>
      {confirmDelete && (
        <ConfirmDialog
          title="Delete metric?"
          message={`It's removed from every report that asks it${usedIn.length ? ` (${plural(usedIn.length, 'report')})` : ''}. Answers already recorded stay in your history.`}
          confirmLabel="Delete"
          cancelLabel="Keep"
          danger
          onCancel={() => setConfirmDelete(false)}
          onConfirm={() => void deleteMetric(db, metricId).then(back)}
        />
      )}
    </>
  )
}
