package dev.brainfence.ui.debug

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.brainfence.data.debug.DebugLogEntry
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val categories = listOf(null, "location", "geofence", "service", "error")
private val categoryLabels = listOf("All", "Location", "Geofence", "Service", "Errors")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugScreen(
    logs: List<DebugLogEntry>,
    selectedCategory: String?,
    onCategorySelected: (String?) -> Unit,
    onClearLogs: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Debug Logs") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        @Suppress("DEPRECATION")
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onClearLogs) {
                        Icon(Icons.Default.Delete, contentDescription = "Clear logs")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            // Filter chips
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                categories.forEachIndexed { index, category ->
                    FilterChip(
                        selected = selectedCategory == category,
                        onClick = { onCategorySelected(category) },
                        label = { Text(categoryLabels[index]) },
                    )
                }
            }

            if (logs.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "No logs yet",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn {
                    items(logs, key = { it.id }) { entry ->
                        DebugLogItem(entry)
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun DebugLogItem(entry: DebugLogEntry) {
    val timeText = remember(entry.timestamp) {
        try {
            val instant = Instant.parse(entry.timestamp)
            val local = instant.atZone(ZoneId.systemDefault())
            val today = LocalDate.now()
            if (local.toLocalDate() == today) {
                local.format(DateTimeFormatter.ofPattern("HH:mm:ss"))
            } else {
                local.format(DateTimeFormatter.ofPattern("MM/dd HH:mm:ss"))
            }
        } catch (_: Exception) {
            entry.timestamp
        }
    }

    val categoryColor = when (entry.category) {
        "location" -> Color(0xFF2196F3)
        "geofence" -> Color(0xFF4CAF50)
        "service" -> Color(0xFFFF9800)
        "error" -> Color(0xFFF44336)
        else -> Color.Gray
    }

    val categoryLabel = when (entry.category) {
        "location" -> "LOC"
        "geofence" -> "GEO"
        "service" -> "SVC"
        "error" -> "ERR"
        else -> entry.category.take(3).uppercase()
    }

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                timeText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            Surface(
                color = categoryColor.copy(alpha = 0.15f),
                shape = MaterialTheme.shapes.extraSmall,
            ) {
                Text(
                    categoryLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = categoryColor,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            entry.message,
            style = MaterialTheme.typography.bodySmall,
        )
        if (entry.lat != null && entry.lng != null) {
            Spacer(Modifier.height(2.dp))
            val locationText = buildString {
                append("%.4f, %.4f".format(entry.lat, entry.lng))
                if (entry.accuracyM != null) {
                    append(" \u00b1${entry.accuracyM.toInt()}m")
                }
            }
            Text(
                locationText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (entry.data != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                entry.data,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
