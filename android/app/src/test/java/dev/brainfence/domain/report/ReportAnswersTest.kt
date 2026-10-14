package dev.brainfence.domain.report

import dev.brainfence.domain.model.Metric
import dev.brainfence.domain.model.MetricKind
import dev.brainfence.domain.model.MetricOption
import dev.brainfence.domain.model.Report
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportAnswersTest {

    private fun metric(id: String, kind: MetricKind, vararg labels: String) = Metric(
        id = id,
        name = id.replaceFirstChar { it.uppercase() },
        description = null,
        kind = kind,
        options = labels.mapIndexed { i, label -> MetricOption("$id-$i", id, label, i) },
    )

    private val nicotine = metric("nicotine", MetricKind.CATEGORICAL, "None", "Patch", "Vape")
    private val anxiety = metric("anxiety", MetricKind.ORDINAL, "1", "2", "3", "4", "5")
    private val report = Report("r", "Morning", includeNote = true, libraryKey = null, listOf(nicotine, anxiety))

    @Test
    fun `entries snapshot name, kind, label, position and option count`() {
        val entries = buildMetricEntries(report, mapOf("nicotine" to "nicotine-1", "anxiety" to "anxiety-3"))
        assertEquals(2, entries.size)
        with(entries[0]) {
            assertEquals("Nicotine", metricName)
            assertEquals(MetricKind.CATEGORICAL, metricKind)
            assertEquals("Patch", optionLabel)
            assertEquals(1, optionIndex)
            assertEquals(3, optionCount)
        }
        with(entries[1]) {
            assertEquals("4", optionLabel)
            assertEquals(3, optionIndex)
            assertEquals(5, optionCount)
        }
    }

    @Test
    fun `entries follow the report's metric order`() {
        val entries = buildMetricEntries(report, mapOf("anxiety" to "anxiety-0", "nicotine" to "nicotine-0"))
        assertEquals(listOf("Nicotine", "Anxiety"), entries.map { it.metricName })
    }

    @Test
    fun `missing or foreign option ids count as unanswered`() {
        val missing = unansweredMetrics(report, mapOf("nicotine" to "anxiety-0"))
        assertEquals(listOf("nicotine", "anxiety"), missing.map { it.id })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `building entries for an incomplete report fails`() {
        buildMetricEntries(report, mapOf("nicotine" to "nicotine-0"))
    }

    @Test
    fun `metric drafts need a name and 2 to 5 distinct non-blank options`() {
        assertNull(validateMetricDraft("Nicotine", listOf("None", "Patch", "Vape")))
        assertTrue(validateMetricDraft(" ", listOf("a", "b")) != null)
        assertTrue(validateMetricDraft("X", listOf("only")) != null)
        assertTrue(validateMetricDraft("X", listOf("1", "2", "3", "4", "5", "6")) != null)
        assertTrue(validateMetricDraft("X", listOf("a", " ")) != null)
        assertTrue(validateMetricDraft("X", listOf("Some", "some ")) != null)
    }
}
