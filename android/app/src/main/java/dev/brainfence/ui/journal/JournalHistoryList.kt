package dev.brainfence.ui.journal

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.brainfence.domain.model.JournalEntry
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

private val TIME_ONLY: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a")
private val MONTH_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d")
private val MONTH_DAY_YEAR: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy")

private fun formatEntryTimestamp(completedAt: String): String {
    val instant = runCatching { Instant.parse(completedAt) }.getOrNull() ?: return completedAt
    val zone = ZoneId.systemDefault()
    val entry = instant.atZone(zone)
    val today = LocalDate.now(zone)
    val entryDate = entry.toLocalDate()
    val daysAgo = ChronoUnit.DAYS.between(entryDate, today)
    val time = entry.format(TIME_ONLY)

    val dayLabel = when {
        daysAgo == 0L -> "Today"
        daysAgo == 1L -> "Yesterday"
        daysAgo in 2..6 -> entryDate.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
        entryDate.year == today.year -> entry.format(MONTH_DAY)
        else -> entry.format(MONTH_DAY_YEAR)
    }
    return "$dayLabel at $time"
}

/** Journal entries, newest first: the Journal tab of Review. */
@Composable
fun JournalHistoryList(
    entries: List<JournalEntry>,
    modifier: Modifier = Modifier,
) {
    if (entries.isEmpty()) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(24.dp),
        ) {
            Text(
                text = "No journal entries yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        LazyColumn(modifier = modifier.fillMaxSize()) {
            items(entries, key = { it.id }) { entry ->
                JournalEntryCard(entry)
            }
        }
    }
}

@Composable
private fun JournalEntryCard(entry: JournalEntry) {
    val dateLabel = remember(entry.completedAt) { formatEntryTimestamp(entry.completedAt) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = dateLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = entry.text,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
