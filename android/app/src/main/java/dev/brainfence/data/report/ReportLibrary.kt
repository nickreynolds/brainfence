package dev.brainfence.data.report

import dev.brainfence.domain.model.MetricKind

/**
 * Starter reports the user can add with one tap. Adding copies the report and
 * its metrics into the user's own tables (tagged with [StarterReport.key]),
 * where they can be edited freely.
 */
object ReportLibrary {

    data class StarterMetric(
        val name: String,
        val description: String?,
        val kind: MetricKind,
        val options: List<String>,
    )

    data class StarterReport(
        val key: String,
        val name: String,
        val description: String,
        val metrics: List<StarterMetric>,
    )

    private val ONE_TO_FIVE = listOf("1", "2", "3", "4", "5")
    private const val ONE_TO_FIVE_HINT = "1 = not at all · 5 = very much"

    val MOOD = StarterReport(
        key = "mood",
        name = "Mood",
        description = "Depression, anxiety and happiness on a 1–5 scale.",
        metrics = listOf(
            StarterMetric("Depression", ONE_TO_FIVE_HINT, MetricKind.ORDINAL, ONE_TO_FIVE),
            StarterMetric("Anxiety", ONE_TO_FIVE_HINT, MetricKind.ORDINAL, ONE_TO_FIVE),
            StarterMetric("Happiness", ONE_TO_FIVE_HINT, MetricKind.ORDINAL, ONE_TO_FIVE),
        ),
    )

    val HABITS = StarterReport(
        key = "habits",
        name = "Habits",
        description = "Examples to edit: a categorical and a none/moderate/a lot metric.",
        metrics = listOf(
            StarterMetric("Nicotine", "Since the last report", MetricKind.CATEGORICAL, listOf("None", "Patch", "Vape")),
            StarterMetric("Alcohol", "Since the last report", MetricKind.ORDINAL, listOf("None", "Moderate", "A lot")),
        ),
    )

    val reports: List<StarterReport> = listOf(MOOD, HABITS)
}
