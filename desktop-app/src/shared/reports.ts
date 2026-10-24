// Port of domain/report/ReportAnswers.kt and data/report/ReportLibrary.kt.

import type { Metric, MetricEntry, MetricKind, Report } from './models'

export const MIN_METRIC_OPTIONS = 2
export const MAX_METRIC_OPTIONS = 5

/** Metrics in [report] without a valid answer in [answers] (metric id → option id). */
export function unansweredMetrics(report: Report, answers: Record<string, string>): Metric[] {
  return report.metrics.filter((m) => !m.options.some((o) => o.id === answers[m.id]))
}

/**
 * Entries to record for a complete set of answers, snapshotting each metric's
 * name/kind and the chosen option's label, position and the option count.
 */
export function buildMetricEntries(report: Report, answers: Record<string, string>): MetricEntry[] {
  const missing = unansweredMetrics(report, answers)
  if (missing.length > 0) throw new Error(`Unanswered: ${missing.map((m) => m.name).join(', ')}`)
  return report.metrics.map((metric) => {
    const index = metric.options.findIndex((o) => o.id === answers[metric.id])
    const option = metric.options[index]
    return {
      metricId: metric.id,
      optionId: option.id,
      metricName: metric.name,
      metricKind: metric.kind,
      optionLabel: option.label,
      optionIndex: index,
      optionCount: metric.options.length,
    }
  })
}

/** Why a metric draft can't be saved, or null if it's valid. */
export function validateMetricDraft(name: string, optionLabels: string[]): string | null {
  if (name.trim() === '') return 'Name is required'
  if (optionLabels.some((l) => l.trim() === '')) return "Options can't be blank"
  if (optionLabels.length < MIN_METRIC_OPTIONS || optionLabels.length > MAX_METRIC_OPTIONS) {
    return `Use ${MIN_METRIC_OPTIONS}–${MAX_METRIC_OPTIONS} options`
  }
  if (new Set(optionLabels.map((l) => l.trim().toLowerCase())).size !== optionLabels.length) {
    return 'Options must be different from each other'
  }
  return null
}

export interface StarterMetric {
  name: string
  description: string | null
  kind: MetricKind
  options: string[]
}

export interface StarterReport {
  key: string
  name: string
  description: string
  metrics: StarterMetric[]
}

const ONE_TO_FIVE = ['1', '2', '3', '4', '5']
const ONE_TO_FIVE_HINT = '1 = not at all · 5 = very much'

export const REPORT_LIBRARY: StarterReport[] = [
  {
    key: 'mood',
    name: 'Mood',
    description: 'Depression, anxiety and happiness on a 1–5 scale.',
    metrics: [
      { name: 'Depression', description: ONE_TO_FIVE_HINT, kind: 'ordinal', options: ONE_TO_FIVE },
      { name: 'Anxiety', description: ONE_TO_FIVE_HINT, kind: 'ordinal', options: ONE_TO_FIVE },
      { name: 'Happiness', description: ONE_TO_FIVE_HINT, kind: 'ordinal', options: ONE_TO_FIVE },
    ],
  },
  {
    key: 'habits',
    name: 'Habits',
    description: 'Examples to edit: a categorical and a none/moderate/a lot metric.',
    metrics: [
      { name: 'Nicotine', description: 'Since the last report', kind: 'categorical', options: ['None', 'Patch', 'Vape'] },
      { name: 'Alcohol', description: 'Since the last report', kind: 'ordinal', options: ['None', 'Moderate', 'A lot'] },
    ],
  },
]

/** Default options for a new metric — the common none/moderate/a lot scale. */
export const NEW_METRIC_OPTIONS = ['None', 'Moderate', 'A lot']
