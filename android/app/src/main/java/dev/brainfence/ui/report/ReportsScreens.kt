package dev.brainfence.ui.report

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.brainfence.data.report.OptionDraft
import dev.brainfence.data.report.ReportLibrary
import dev.brainfence.domain.model.Metric
import dev.brainfence.domain.model.MetricKind
import dev.brainfence.domain.model.Report
import dev.brainfence.domain.report.MAX_METRIC_OPTIONS
import dev.brainfence.domain.report.MIN_METRIC_OPTIONS

private fun Metric.summary(): String =
    (if (kind == MetricKind.CATEGORICAL) "Categories: " else "Scale: ") +
        options.joinToString(" / ") { it.label }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BackScaffold(
    title: String,
    onBack: () -> Unit,
    actions: @Composable () -> Unit = {},
    content: @Composable (Modifier) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = { actions() },
            )
        },
    ) { padding ->
        content(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        )
    }
}

@Composable
private fun ConfirmDeleteDialog(title: String, text: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = { onDismiss(); onConfirm() }) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Keep") } },
    )
}

// ── Reports list ─────────────────────────────────────────────────────

/** Reports, starter reports and every metric: the Reports tab of Review, which has the New report button. */
@Composable
fun ReportsList(
    reports: List<Report>,
    metrics: List<Metric>,
    starters: List<ReportLibrary.StarterReport>,
    onOpenReport: (String) -> Unit,
    onOpenMetric: (String) -> Unit,
    onAddStarter: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "A report is a set of metrics you answer from a Report task or a routine's report " +
                    "step. Metrics are shared: every report that asks one adds to the same history.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(reports, key = { it.id }) { report ->
            Card(modifier = Modifier.fillMaxWidth().clickable { onOpenReport(report.id) }) {
                Column(Modifier.padding(16.dp)) {
                    Text(report.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = report.metrics.joinToString { it.name }.ifBlank { "No metrics yet" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        items(starters, key = { "starter-${it.key}" }) { starter ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Starter: ${starter.name}", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = starter.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { onAddStarter(starter.key) }) { Text("Add") }
                }
            }
        }
        if (metrics.isNotEmpty()) {
            item {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text("All metrics", style = MaterialTheme.typography.titleMedium)
            }
            items(metrics, key = { "metric-${it.id}" }) { metric ->
                Column(Modifier.fillMaxWidth().clickable { onOpenMetric(metric.id) }.padding(vertical = 4.dp)) {
                    Text(metric.name, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = metric.summary(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        // Room to scroll the last metric clear of the New report button.
        item { Spacer(Modifier.height(88.dp)) }
    }
}

// ── Report editor ────────────────────────────────────────────────────

@Composable
fun ReportEditorScreen(
    form: ReportEditorForm,
    report: Report?,
    addableMetrics: List<Metric>,
    onSetName: (String) -> Unit,
    onSetIncludeNote: (Boolean) -> Unit,
    onSave: () -> Unit,
    onAddMetric: (String) -> Unit,
    onCreateMetric: () -> Unit,
    onOpenMetric: (String) -> Unit,
    onRemoveMetric: (String) -> Unit,
    onMoveMetric: (metricId: String, delta: Int) -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    BackScaffold(
        title = form.name.ifBlank { "Report" },
        onBack = onBack,
        actions = {
            IconButton(onClick = { confirmDelete = true }) {
                Icon(Icons.Default.Delete, contentDescription = "Delete report")
            }
        },
    ) { modifier ->
        if (!form.isLoaded || report == null) {
            Row(modifier, horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator()
            }
            return@BackScaffold
        }
        LazyColumn(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = form.name,
                    onValueChange = onSetName,
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Ask for an optional note", modifier = Modifier.weight(1f))
                    Switch(checked = form.includeNote, onCheckedChange = onSetIncludeNote)
                }
            }
            if (form.error != null) {
                item { Text(form.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
            item { Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) { Text("Save") } }
            item {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text("Metrics, in the order they're asked", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "Changes here save immediately.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            itemsIndexed(report.metrics, key = { _, m -> m.id }) { index, metric ->
                Card(modifier = Modifier.fillMaxWidth().clickable { onOpenMetric(metric.id) }) {
                    Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(metric.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = metric.summary(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { onMoveMetric(metric.id, -1) }, enabled = index > 0) {
                            Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move up")
                        }
                        IconButton(onClick = { onMoveMetric(metric.id, 1) }, enabled = index < report.metrics.lastIndex) {
                            Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move down")
                        }
                        IconButton(onClick = { onRemoveMetric(metric.id) }) {
                            Icon(Icons.Default.Close, contentDescription = "Remove from report")
                        }
                    }
                }
            }
            item {
                TextButton(onClick = { showAddDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Add metric")
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Add metric") },
            text = {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    item {
                        TextButton(onClick = { showAddDialog = false; onCreateMetric() }) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("New metric")
                        }
                    }
                    items(addableMetrics, key = { it.id }) { metric ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable { showAddDialog = false; onAddMetric(metric.id) }
                                .padding(vertical = 8.dp),
                        ) {
                            Text(metric.name, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = metric.summary(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showAddDialog = false }) { Text("Cancel") } },
        )
    }

    if (confirmDelete) {
        ConfirmDeleteDialog(
            title = "Delete report?",
            text = "Its metrics are kept (they may be used by other reports) and past answers stay in your history. " +
                "Tasks and routine steps using this report will need a new one.",
            onConfirm = onDelete,
            onDismiss = { confirmDelete = false },
        )
    }
}

// ── Metric editor ────────────────────────────────────────────────────

@Composable
fun MetricEditorScreen(
    form: MetricForm,
    usedIn: List<String>,
    onUpdate: ((MetricForm) -> MetricForm) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }

    BackScaffold(
        title = form.name.ifBlank { "Metric" },
        onBack = onBack,
        actions = {
            IconButton(onClick = { confirmDelete = true }) {
                Icon(Icons.Default.Delete, contentDescription = "Delete metric")
            }
        },
    ) { modifier ->
        if (!form.isLoaded) {
            Row(modifier, horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator()
            }
            return@BackScaffold
        }
        LazyColumn(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = form.name,
                    onValueChange = { v -> onUpdate { it.copy(name = v) } },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                OutlinedTextField(
                    value = form.description,
                    onValueChange = { v -> onUpdate { it.copy(description = v) } },
                    label = { Text("Hint (optional)") },
                    placeholder = { Text("e.g. 1 = not at all · 5 = very much") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Text("Answers are", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = form.kind == MetricKind.ORDINAL,
                        onClick = { onUpdate { it.copy(kind = MetricKind.ORDINAL) } },
                        label = { Text("A scale (ordered)") },
                    )
                    FilterChip(
                        selected = form.kind == MetricKind.CATEGORICAL,
                        onClick = { onUpdate { it.copy(kind = MetricKind.CATEGORICAL) } },
                        label = { Text("Categories") },
                    )
                }
                Text(
                    text = if (form.kind == MetricKind.ORDINAL) {
                        "Options run from least to most, e.g. None → Moderate → A lot."
                    } else {
                        "Options are just different kinds, e.g. None / Patch / Vape."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item { Text("Options ($MIN_METRIC_OPTIONS–$MAX_METRIC_OPTIONS)", style = MaterialTheme.typography.titleSmall) }
            itemsIndexed(form.options) { index, option ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = option.label,
                        onValueChange = { v ->
                            onUpdate { f -> f.copy(options = f.options.toMutableList().also { it[index] = option.copy(label = v) }) }
                        },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = { onUpdate { f -> f.copy(options = f.options.swap(index, index - 1)) } },
                        enabled = index > 0,
                    ) { Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move up") }
                    IconButton(
                        onClick = { onUpdate { f -> f.copy(options = f.options.swap(index, index + 1)) } },
                        enabled = index < form.options.lastIndex,
                    ) { Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move down") }
                    IconButton(
                        onClick = { onUpdate { f -> f.copy(options = f.options.filterIndexed { i, _ -> i != index }) } },
                        enabled = form.options.size > MIN_METRIC_OPTIONS,
                    ) { Icon(Icons.Default.Close, contentDescription = "Remove option") }
                }
            }
            item {
                TextButton(
                    onClick = { onUpdate { f -> f.copy(options = f.options + OptionDraft(id = null, label = "")) } },
                    enabled = form.options.size < MAX_METRIC_OPTIONS,
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Add option")
                }
            }
            item {
                Text(
                    text = buildString {
                        if (usedIn.isNotEmpty()) append("Used in: ${usedIn.joinToString()}. ")
                        append("Renaming or reordering options doesn't change answers already recorded.")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (form.error != null) {
                item { Text(form.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
            item {
                Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) { Text("Save") }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (confirmDelete) {
        ConfirmDeleteDialog(
            title = "Delete metric?",
            text = "It's removed from every report that asks it. Answers already recorded stay in your history.",
            onConfirm = onDelete,
            onDismiss = { confirmDelete = false },
        )
    }
}

private fun <T> List<T>.swap(i: Int, j: Int): List<T> {
    if (i !in indices || j !in indices) return this
    return toMutableList().also { val t = it[i]; it[i] = it[j]; it[j] = t }
}
