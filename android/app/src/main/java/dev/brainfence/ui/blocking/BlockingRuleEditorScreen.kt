package dev.brainfence.ui.blocking

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.brainfence.domain.model.Task

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BlockingRuleEditorScreen(
    state: EditorUiState,
    installedApps: List<InstalledApp>,
    tasks: List<Task>,
    onUpdateName: (String) -> Unit,
    onToggleApp: (String) -> Unit,
    onAddDomain: (String) -> Unit,
    onRemoveDomain: (String) -> Unit,
    onToggleConditionTask: (String) -> Unit,
    onSetConditionLogic: (String) -> Unit,
    onSave: () -> Unit,
    onCancelPendingChanges: () -> Unit,
    onClearError: () -> Unit,
    onBack: () -> Unit,
) {
    var appSearchQuery by rememberSaveable { mutableStateOf("") }
    var domainInput by rememberSaveable { mutableStateOf("") }
    var showAppPicker by rememberSaveable { mutableStateOf(false) }
    var showTaskPicker by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.isNew) "New Blocking Rule" else "Edit Blocking Rule") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = onSave,
                        enabled = !state.isSaving,
                    ) {
                        if (state.isSaving) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Save, contentDescription = "Save")
                        }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .padding(horizontal = 16.dp),
        ) {
            // Pending changes banner
            if (state.hasPendingChanges) {
                item(key = "pending_banner") {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        ),
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Schedule,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.tertiary,
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Changes pending",
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                val applyAt = state.changesApplyAt ?: ""
                                Text(
                                    text = if (applyAt.isNotBlank()) "Will apply at ${applyAt.substringBefore("T")} ${applyAt.substringAfter("T").take(5)}" else "Processing...",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            TextButton(onClick = onCancelPendingChanges) {
                                Text("Cancel")
                            }
                        }
                    }
                }
            }

            // Error banner
            if (state.error != null) {
                item(key = "error") {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = state.error,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = onClearError) {
                                Icon(Icons.Default.Close, contentDescription = "Dismiss")
                            }
                        }
                    }
                }
            }

            // Save note for existing rules
            if (!state.isNew) {
                item(key = "time_lock_note") {
                    Text(
                        text = "Changes to existing rules are time-locked and take 24 hours to apply.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 16.dp),
                    )
                }
            }

            // Rule name
            item(key = "name") {
                OutlinedTextField(
                    value = state.name,
                    onValueChange = onUpdateName,
                    label = { Text("Rule name") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                )
            }

            // --- Blocked Apps ---
            item(key = "apps_header") {
                SectionHeader("Blocked Apps")
            }

            if (state.selectedApps.isNotEmpty()) {
                item(key = "selected_apps") {
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        state.selectedApps.forEach { pkg ->
                            val app = installedApps.find { it.packageName == pkg }
                            InputChip(
                                selected = true,
                                onClick = { onToggleApp(pkg) },
                                label = { Text(app?.label ?: pkg.substringAfterLast('.')) },
                                leadingIcon = {
                                    app?.icon?.let {
                                        Image(bitmap = it, contentDescription = null, modifier = Modifier.size(18.dp))
                                    }
                                },
                                trailingIcon = {
                                    Icon(Icons.Default.Close, contentDescription = "Remove", modifier = Modifier.size(16.dp))
                                },
                            )
                        }
                    }
                }
            }

            item(key = "app_picker_toggle") {
                TextButton(onClick = { showAppPicker = !showAppPicker }) {
                    Text(if (showAppPicker) "Hide app list" else "Select apps to block...")
                }
            }

            if (showAppPicker) {
                item(key = "app_search") {
                    OutlinedTextField(
                        value = appSearchQuery,
                        onValueChange = { appSearchQuery = it },
                        label = { Text("Search apps") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                    )
                }

                val filteredApps = installedApps.filter { app ->
                    appSearchQuery.isBlank() ||
                        app.label.contains(appSearchQuery, ignoreCase = true) ||
                        app.packageName.contains(appSearchQuery, ignoreCase = true)
                }
                items(filteredApps, key = { "app_${it.packageName}" }) { app ->
                    ListItem(
                        modifier = Modifier.clickable { onToggleApp(app.packageName) },
                        headlineContent = { Text(app.label) },
                        supportingContent = {
                            Text(
                                text = app.packageName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        leadingContent = {
                            app.icon?.let {
                                Image(bitmap = it, contentDescription = null, modifier = Modifier.size(32.dp))
                            }
                        },
                        trailingContent = {
                            Checkbox(
                                checked = app.packageName in state.selectedApps,
                                onCheckedChange = { onToggleApp(app.packageName) },
                            )
                        },
                    )
                }

                item(key = "app_picker_end") {
                    Spacer(Modifier.height(8.dp))
                }
            }

            // --- Blocked Domains ---
            item(key = "domains_header") {
                SectionHeader("Blocked Domains")
            }

            if (state.blockedDomains.isNotEmpty()) {
                item(key = "domain_chips") {
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        state.blockedDomains.forEach { domain ->
                            InputChip(
                                selected = true,
                                onClick = { onRemoveDomain(domain) },
                                label = { Text(domain) },
                                trailingIcon = {
                                    Icon(Icons.Default.Close, contentDescription = "Remove", modifier = Modifier.size(16.dp))
                                },
                            )
                        }
                    }
                }
            }

            item(key = "domain_input") {
                OutlinedTextField(
                    value = domainInput,
                    onValueChange = { domainInput = it },
                    label = { Text("Add domain (e.g. twitter.com)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = {
                        if (domainInput.isNotBlank()) {
                            onAddDomain(domainInput)
                            domainInput = ""
                        }
                    }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                )
            }

            // --- Condition Tasks ---
            item(key = "tasks_header") {
                SectionHeader("Condition Tasks")
                Text(
                    text = "Tasks that must be completed to unblock the apps above. Blocking activates when tasks are past their due time.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }

            // Condition logic toggle
            item(key = "condition_logic") {
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                ) {
                    SegmentedButton(
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                        onClick = { onSetConditionLogic("all") },
                        selected = state.conditionLogic == "all",
                    ) { Text("All tasks") }
                    SegmentedButton(
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                        onClick = { onSetConditionLogic("any") },
                        selected = state.conditionLogic == "any",
                    ) { Text("Any task") }
                }
            }

            item(key = "task_picker_toggle") {
                TextButton(onClick = { showTaskPicker = !showTaskPicker }) {
                    Text(if (showTaskPicker) "Hide task list" else "Select condition tasks...")
                }
            }

            if (state.conditionTaskIds.isNotEmpty()) {
                item(key = "selected_tasks") {
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        state.conditionTaskIds.forEach { taskId ->
                            val task = tasks.find { it.id == taskId }
                            InputChip(
                                selected = true,
                                onClick = { onToggleConditionTask(taskId) },
                                label = { Text(task?.title ?: taskId.take(8)) },
                                trailingIcon = {
                                    Icon(Icons.Default.Close, contentDescription = "Remove", modifier = Modifier.size(16.dp))
                                },
                            )
                        }
                    }
                }
            }

            if (showTaskPicker) {
                items(tasks, key = { "task_${it.id}" }) { task ->
                    ListItem(
                        modifier = Modifier.clickable { onToggleConditionTask(task.id) },
                        headlineContent = { Text(task.title) },
                        supportingContent = {
                            val verif = task.verificationType ?: "manual"
                            Text("${task.taskType} \u00b7 $verif")
                        },
                        trailingContent = {
                            Checkbox(
                                checked = task.id in state.conditionTaskIds,
                                onCheckedChange = { onToggleConditionTask(task.id) },
                            )
                        },
                    )
                }

                item(key = "task_picker_end") {
                    Spacer(Modifier.height(8.dp))
                }
            }

            // Bottom spacer for FAB clearance
            item(key = "bottom_spacer") {
                Spacer(Modifier.height(80.dp))
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    )
}
