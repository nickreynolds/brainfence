package dev.brainfence.ui.report

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.brainfence.domain.model.Report
import dev.brainfence.domain.model.ReportSubmission
import dev.brainfence.domain.report.unansweredMetrics

/**
 * Fill in a [report]: one row of option chips per metric, plus an optional
 * note. Submit is enabled once every metric has an answer.
 *
 * @param onSubmit metric id → option id, and the note (blank if none).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReportForm(
    report: Report,
    isSubmitting: Boolean,
    onSubmit: (answers: Map<String, String>, note: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var answers by rememberSaveable(report.id) { mutableStateOf(mapOf<String, String>()) }
    var note by rememberSaveable(report.id) { mutableStateOf("") }
    val complete = unansweredMetrics(report, answers).isEmpty()

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (report.metrics.isEmpty()) {
            Text(
                text = "This report has no metrics yet. Add some under ⋮ → Reports.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        for (metric in report.metrics) {
            Column {
                Text(metric.name, style = MaterialTheme.typography.titleSmall)
                if (!metric.description.isNullOrBlank()) {
                    Text(
                        text = metric.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (option in metric.options) {
                        FilterChip(
                            selected = answers[metric.id] == option.id,
                            onClick = { answers = answers + (metric.id to option.id) },
                            label = { Text(option.label) },
                            enabled = !isSubmitting,
                        )
                    }
                }
            }
        }
        if (report.includeNote) {
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("Note (optional)") },
                minLines = 2,
                enabled = !isSubmitting,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Button(
            onClick = { onSubmit(answers, note) },
            enabled = complete && report.metrics.isNotEmpty() && !isSubmitting,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (isSubmitting) "Saving…" else "Submit")
        }
    }
}

/** Read-only view of what was recorded. */
@Composable
fun ReportSubmissionSummary(submission: ReportSubmission, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (entry in submission.entries) {
            Row {
                Text(
                    text = "${entry.metricName}: ",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = entry.optionLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
        if (!submission.note.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "“${submission.note}”",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
