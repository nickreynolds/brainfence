package dev.brainfence.domain.report

import dev.brainfence.domain.model.Metric
import dev.brainfence.domain.model.MetricEntry
import dev.brainfence.domain.model.Report

const val MIN_METRIC_OPTIONS = 2
const val MAX_METRIC_OPTIONS = 5

/** Metrics in [report] without a valid answer in [answers] (metric id → option id). */
fun unansweredMetrics(report: Report, answers: Map<String, String>): List<Metric> =
    report.metrics.filter { metric -> metric.options.none { it.id == answers[metric.id] } }

/**
 * The entries to record for a complete set of [answers], snapshotting each
 * metric's name/kind and the chosen option's label, position and the option
 * count, so later edits to the metric never change what was recorded.
 *
 * @throws IllegalArgumentException if any metric is unanswered.
 */
fun buildMetricEntries(report: Report, answers: Map<String, String>): List<MetricEntry> {
    val missing = unansweredMetrics(report, answers)
    require(missing.isEmpty()) { "Unanswered: ${missing.joinToString { it.name }}" }
    return report.metrics.map { metric ->
        val index = metric.options.indexOfFirst { it.id == answers[metric.id] }
        val option = metric.options[index]
        MetricEntry(
            metricId = metric.id,
            optionId = option.id,
            metricName = metric.name,
            metricKind = metric.kind,
            optionLabel = option.label,
            optionIndex = index,
            optionCount = metric.options.size,
        )
    }
}

/** Why a metric draft can't be saved, or null if it's valid. */
fun validateMetricDraft(name: String, optionLabels: List<String>): String? = when {
    name.isBlank() -> "Name is required"
    optionLabels.any { it.isBlank() } -> "Options can't be blank"
    optionLabels.size !in MIN_METRIC_OPTIONS..MAX_METRIC_OPTIONS ->
        "Use $MIN_METRIC_OPTIONS–$MAX_METRIC_OPTIONS options"
    optionLabels.map { it.trim().lowercase() }.toSet().size != optionLabels.size ->
        "Options must be different from each other"
    else -> null
}
