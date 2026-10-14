package dev.brainfence.domain.model

/** Whether a metric's options are ranked (none < a lot, 1..5) or just named categories. */
enum class MetricKind(val key: String) {
    ORDINAL("ordinal"),
    CATEGORICAL("categorical");

    companion object {
        fun from(key: String?): MetricKind = entries.firstOrNull { it.key == key } ?: ORDINAL
    }
}

data class MetricOption(
    val id: String,
    val metricId: String,
    val label: String,
    val sortOrder: Int,
)

/** Something tracked over time, e.g. "Anxiety" or "Nicotine". Options are in order. */
data class Metric(
    val id: String,
    val name: String,
    val description: String?,
    val kind: MetricKind,
    val options: List<MetricOption>,
)

/** A reusable set of metrics, filled in by a report task or a routine report step. */
data class Report(
    val id: String,
    val name: String,
    val includeNote: Boolean,
    /** Set when added from the built-in starter library. */
    val libraryKey: String?,
    /** In the order they're asked. */
    val metrics: List<Metric>,
)

/** One answer as recorded, with its metric/option snapshotted at the time. */
data class MetricEntry(
    val metricId: String?,
    val optionId: String?,
    val metricName: String,
    val metricKind: MetricKind,
    val optionLabel: String,
    val optionIndex: Int,
    val optionCount: Int,
)

/** One filled-in report. */
data class ReportSubmission(
    val id: String,
    val reportId: String?,
    val taskId: String?,
    val routineStepId: String?,
    val reportName: String,
    val note: String?,
    val submittedAt: String,
    val entries: List<MetricEntry>,
)
