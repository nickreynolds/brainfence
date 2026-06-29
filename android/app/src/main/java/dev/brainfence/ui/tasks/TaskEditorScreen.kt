package dev.brainfence.ui.tasks

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.InputChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskEditorScreen(
    state: TaskEditorState,
    installedApps: List<dev.brainfence.data.apps.InstalledApp>,
    onUpdateTitle: (String) -> Unit,
    onUpdateDescription: (String) -> Unit,
    onSetTaskType: (String) -> Unit,
    onSetVerificationType: (String) -> Unit,
    onSetDurationSeconds: (Int) -> Unit,
    onSetLatitude: (String) -> Unit,
    onSetLongitude: (String) -> Unit,
    onSetRadiusMeters: (Int) -> Unit,
    onSetMeditationSeconds: (Int) -> Unit,
    onToggleCompanionApp: (String) -> Unit,
    onAddRoutineStep: () -> Unit,
    onRemoveRoutineStep: (String) -> Unit,
    onUpdateRoutineStep: (String, (EditableStep) -> EditableStep) -> Unit,
    onCreateSupersetGroup: (List<String>) -> Unit,
    onRemoveSupersetGroup: (String) -> Unit,
    onSetRecurrenceType: (String?) -> Unit,
    onToggleWeeklyDay: (String) -> Unit,
    onSetBlockingCondition: (Boolean) -> Unit,
    onSetHomeOnlyBlocking: (Boolean) -> Unit,
    onSetAvailableFrom: (String) -> Unit,
    onSetDueAt: (String) -> Unit,
    onNextStep: () -> Unit,
    onPrevStep: () -> Unit,
    onSave: () -> Unit,
    onClearError: () -> Unit,
    onBack: () -> Unit,
) {
    val titlePrefix = if (state.editingTaskId != null) "Edit Task" else "New Task"
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("$titlePrefix — Step ${state.currentStep + 1} of 3")
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (state.currentStep > 0) onPrevStep() else onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        bottomBar = {
            WizardBottomBar(
                currentStep = state.currentStep,
                isSaving = state.isSaving,
                isEditing = state.editingTaskId != null,
                onNext = onNextStep,
                onBack = onPrevStep,
                onSave = onSave,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // Step indicator
            StepIndicator(currentStep = state.currentStep)

            // Error banner
            if (state.error != null) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = state.error,
                            modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        IconButton(onClick = onClearError, modifier = Modifier.size(24.dp)) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Dismiss",
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }

            // Animated step content
            AnimatedContent(
                targetState = state.currentStep,
                transitionSpec = {
                    if (targetState > initialState) {
                        slideInHorizontally { it } togetherWith slideOutHorizontally { -it }
                    } else {
                        slideInHorizontally { -it } togetherWith slideOutHorizontally { it }
                    }
                },
                label = "wizard_step",
            ) { step ->
                when (step) {
                    0 -> BasicsStep(
                        state = state,
                        onUpdateTitle = onUpdateTitle,
                        onUpdateDescription = onUpdateDescription,
                        onSetTaskType = onSetTaskType,
                    )
                    1 -> ConfigStep(
                        state = state,
                        installedApps = installedApps,
                        onSetVerificationType = onSetVerificationType,
                        onSetDurationSeconds = onSetDurationSeconds,
                        onSetLatitude = onSetLatitude,
                        onSetLongitude = onSetLongitude,
                        onSetRadiusMeters = onSetRadiusMeters,
                        onSetMeditationSeconds = onSetMeditationSeconds,
                        onToggleCompanionApp = onToggleCompanionApp,
                        onAddRoutineStep = onAddRoutineStep,
                        onRemoveRoutineStep = onRemoveRoutineStep,
                        onUpdateRoutineStep = onUpdateRoutineStep,
                        onCreateSupersetGroup = onCreateSupersetGroup,
                        onRemoveSupersetGroup = onRemoveSupersetGroup,
                    )
                    2 -> ScheduleStep(
                        state = state,
                        onSetRecurrenceType = onSetRecurrenceType,
                        onToggleWeeklyDay = onToggleWeeklyDay,
                        onSetBlockingCondition = onSetBlockingCondition,
                        onSetHomeOnlyBlocking = onSetHomeOnlyBlocking,
                        onSetAvailableFrom = onSetAvailableFrom,
                        onSetDueAt = onSetDueAt,
                    )
                }
            }
        }
    }
}

@Composable
private fun StepIndicator(currentStep: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        val labels = listOf("Basics", "Configure", "Schedule")
        labels.forEachIndexed { index, label ->
            if (index > 0) {
                Box(
                    modifier = Modifier
                        .width(32.dp)
                        .height(2.dp)
                        .align(Alignment.CenterVertically)
                        .background(
                            if (index <= currentStep) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant,
                        ),
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(
                            if (index <= currentStep) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "${index + 1}",
                        color = if (index <= currentStep) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (index <= currentStep) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun WizardBottomBar(
    currentStep: Int,
    isSaving: Boolean,
    isEditing: Boolean,
    onNext: () -> Unit,
    onBack: () -> Unit,
    onSave: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        if (currentStep > 0) {
            OutlinedButton(onClick = onBack) {
                Text("Back")
            }
        } else {
            Spacer(Modifier.width(1.dp))
        }
        if (currentStep < 2) {
            Button(onClick = onNext) {
                Text("Next")
            }
        } else {
            val saveLabel = when {
                isSaving && isEditing -> "Saving..."
                isSaving -> "Creating..."
                isEditing -> "Save Changes"
                else -> "Create Task"
            }
            Button(onClick = onSave, enabled = !isSaving) {
                Text(saveLabel)
            }
        }
    }
}

// ==================== Step 0: Basics ====================

@Composable
private fun BasicsStep(
    state: TaskEditorState,
    onUpdateTitle: (String) -> Unit,
    onUpdateDescription: (String) -> Unit,
    onSetTaskType: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = state.title,
                onValueChange = onUpdateTitle,
                label = { Text("Task Title") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            OutlinedTextField(
                value = state.description,
                onValueChange = onUpdateDescription,
                label = { Text("Description (optional)") },
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Text(
                text = "Task Type",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            TaskTypeSelector(
                selected = state.taskType,
                onSelect = onSetTaskType,
            )
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun TaskTypeSelector(selected: String, onSelect: (String) -> Unit) {
    val types = listOf(
        "simple" to "Simple",
        "timed" to "Timed",
        "routine" to "Routine",
        "workout" to "Workout",
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        types.chunked(2).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEach { (value, label) ->
                    val isSelected = selected == value
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onSelect(value) },
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                             else MaterialTheme.colorScheme.surfaceContainerLow,
                        ),
                        border = if (isSelected) CardDefaults.outlinedCardBorder()
                                 else null,
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.titleSmall,
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                                        else MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = taskTypeDescription(value),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun taskTypeDescription(type: String): String = when (type) {
    "simple" -> "Tap to complete"
    "timed" -> "Requires a timed session"
    "routine" -> "Multi-step checklist"
    "workout" -> "Exercise tracking with sets"
    else -> ""
}

// ==================== Step 1: Configuration ====================

@Composable
private fun ConfigStep(
    state: TaskEditorState,
    installedApps: List<dev.brainfence.data.apps.InstalledApp>,
    onSetVerificationType: (String) -> Unit,
    onSetDurationSeconds: (Int) -> Unit,
    onSetLatitude: (String) -> Unit,
    onSetLongitude: (String) -> Unit,
    onSetRadiusMeters: (Int) -> Unit,
    onSetMeditationSeconds: (Int) -> Unit,
    onToggleCompanionApp: (String) -> Unit,
    onAddRoutineStep: () -> Unit,
    onRemoveRoutineStep: (String) -> Unit,
    onUpdateRoutineStep: (String, (EditableStep) -> EditableStep) -> Unit,
    onCreateSupersetGroup: (List<String>) -> Unit,
    onRemoveSupersetGroup: (String) -> Unit,
) {
    when (state.taskType) {
        "simple" -> SimpleConfigContent(
            state = state,
            installedApps = installedApps,
            onSetVerificationType = onSetVerificationType,
            onSetLatitude = onSetLatitude,
            onSetLongitude = onSetLongitude,
            onSetRadiusMeters = onSetRadiusMeters,
            onSetMeditationSeconds = onSetMeditationSeconds,
            onToggleCompanionApp = onToggleCompanionApp,
        )
        "timed" -> TimedConfigContent(
            durationSeconds = state.durationSeconds,
            onSetDurationSeconds = onSetDurationSeconds,
        )
        "routine", "workout" -> RoutineConfigContent(
            state = state,
            onAddRoutineStep = onAddRoutineStep,
            onRemoveRoutineStep = onRemoveRoutineStep,
            onUpdateRoutineStep = onUpdateRoutineStep,
            onCreateSupersetGroup = onCreateSupersetGroup,
            onRemoveSupersetGroup = onRemoveSupersetGroup,
        )
    }
}

@Composable
private fun SimpleConfigContent(
    state: TaskEditorState,
    installedApps: List<dev.brainfence.data.apps.InstalledApp>,
    onSetVerificationType: (String) -> Unit,
    onSetLatitude: (String) -> Unit,
    onSetLongitude: (String) -> Unit,
    onSetRadiusMeters: (Int) -> Unit,
    onSetMeditationSeconds: (Int) -> Unit,
    onToggleCompanionApp: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Verification Method",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            val verificationTypes = listOf(
                "manual" to "Manual",
                "gps" to "GPS Location",
                "meditation" to "Meditation",
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                verificationTypes.forEach { (value, label) ->
                    FilterChip(
                        selected = state.verificationType == value,
                        onClick = { onSetVerificationType(value) },
                        label = { Text(label) },
                    )
                }
            }
        }

        when (state.verificationType) {
            "gps" -> {
                item {
                    Text("GPS Location", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        OutlinedTextField(
                            value = state.latitude,
                            onValueChange = onSetLatitude,
                            label = { Text("Latitude") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = state.longitude,
                            onValueChange = onSetLongitude,
                            label = { Text("Longitude") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = state.radiusMeters.toString(),
                        onValueChange = { it.toIntOrNull()?.let(onSetRadiusMeters) },
                        label = { Text("Radius (meters)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            "meditation" -> {
                item {
                    Text("Meditation Settings", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    val minutes = state.meditationSeconds / 60
                    OutlinedTextField(
                        value = minutes.toString(),
                        onValueChange = { it.toIntOrNull()?.let { m -> onSetMeditationSeconds(m * 60) } },
                        label = { Text("Duration (minutes)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                companionAppPickerSection(
                    selected = state.companionApps,
                    installedApps = installedApps,
                    onToggle = onToggleCompanionApp,
                )
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun TimedConfigContent(
    durationSeconds: Int,
    onSetDurationSeconds: (Int) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Spacer(Modifier.height(4.dp))
            Text("Session Duration", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            val minutes = durationSeconds / 60
            val seconds = durationSeconds % 60
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = minutes.toString(),
                    onValueChange = { text ->
                        val m = text.toIntOrNull() ?: 0
                        onSetDurationSeconds(m * 60 + seconds)
                    },
                    label = { Text("Minutes") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = seconds.toString(),
                    onValueChange = { text ->
                        val s = (text.toIntOrNull() ?: 0).coerceIn(0, 59)
                        onSetDurationSeconds(minutes * 60 + s)
                    },
                    label = { Text("Seconds") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoutineConfigContent(
    state: TaskEditorState,
    onAddRoutineStep: () -> Unit,
    onRemoveRoutineStep: (String) -> Unit,
    onUpdateRoutineStep: (String, (EditableStep) -> EditableStep) -> Unit,
    onCreateSupersetGroup: (List<String>) -> Unit,
    onRemoveSupersetGroup: (String) -> Unit,
) {
    var selectedForSuperset by remember { mutableStateOf(setOf<String>()) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (state.taskType == "workout") "Exercises" else "Steps",
                    style = MaterialTheme.typography.titleSmall,
                )
                Row {
                    if (selectedForSuperset.size >= 2) {
                        TextButton(onClick = {
                            onCreateSupersetGroup(selectedForSuperset.toList())
                            selectedForSuperset = emptySet()
                        }) {
                            Text("Group as Superset")
                        }
                    }
                    TextButton(onClick = onAddRoutineStep) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Add")
                    }
                }
            }
        }

        itemsIndexed(state.routineSteps, key = { _, step -> step.id }) { _, step ->
            EditableStepCard(
                step = step,
                isWorkout = state.taskType == "workout",
                isSelectedForSuperset = step.id in selectedForSuperset,
                onToggleSelectForSuperset = {
                    selectedForSuperset = if (step.id in selectedForSuperset) {
                        selectedForSuperset - step.id
                    } else {
                        selectedForSuperset + step.id
                    }
                },
                onRemove = { onRemoveRoutineStep(step.id) },
                onUpdate = { update -> onUpdateRoutineStep(step.id, update) },
                onRemoveSupersetGroup = step.supersetGroup?.let { group ->
                    { onRemoveSupersetGroup(group) }
                },
            )
        }

        item {
            if (state.routineSteps.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = if (state.taskType == "workout") "No exercises yet"
                                   else "No steps yet",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = onAddRoutineStep) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (state.taskType == "workout") "Add Exercise" else "Add Step")
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditableStepCard(
    step: EditableStep,
    isWorkout: Boolean,
    isSelectedForSuperset: Boolean,
    onToggleSelectForSuperset: () -> Unit,
    onRemove: () -> Unit,
    onUpdate: ((EditableStep) -> EditableStep) -> Unit,
    onRemoveSupersetGroup: (() -> Unit)?,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                step.supersetGroup != null -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f)
                isSelectedForSuperset -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                else -> MaterialTheme.colorScheme.surfaceContainerLow
            },
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Superset selection checkbox (visual)
                FilterChip(
                    selected = isSelectedForSuperset,
                    onClick = onToggleSelectForSuperset,
                    label = { },
                    modifier = Modifier.size(32.dp),
                )
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = step.title,
                    onValueChange = { title -> onUpdate { it.copy(title = title) } },
                    label = { Text(if (isWorkout) "Exercise name" else "Step name") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onRemove) {
                    Icon(Icons.Default.Delete, contentDescription = "Remove", modifier = Modifier.size(20.dp))
                }
            }

            Spacer(Modifier.height(8.dp))

            // Step type selector
            var typeExpanded by remember { mutableStateOf(false) }
            val stepTypes = if (isWorkout) {
                listOf(
                    "weight_reps" to "Weight & Reps",
                    "just_reps" to "Reps Only",
                    "timed" to "Timed",
                    "checkbox" to "Checkbox",
                )
            } else {
                listOf(
                    "checkbox" to "Checkbox",
                    "timed" to "Timed",
                    "weight_reps" to "Weight & Reps",
                    "just_reps" to "Reps Only",
                )
            }
            val selectedLabel = stepTypes.find { it.first == step.stepType }?.second ?: step.stepType

            ExposedDropdownMenuBox(
                expanded = typeExpanded,
                onExpandedChange = { typeExpanded = it },
            ) {
                OutlinedTextField(
                    value = selectedLabel,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Type") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = typeExpanded) },
                    modifier = Modifier
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth(),
                )
                ExposedDropdownMenu(
                    expanded = typeExpanded,
                    onDismissRequest = { typeExpanded = false },
                ) {
                    stepTypes.forEach { (value, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                onUpdate { it.copy(stepType = value) }
                                typeExpanded = false
                            },
                        )
                    }
                }
            }

            // Type-specific config
            when (step.stepType) {
                "weight_reps", "just_reps" -> {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = step.defaultSets.toString(),
                        onValueChange = { text ->
                            text.toIntOrNull()?.let { sets ->
                                onUpdate { it.copy(defaultSets = sets.coerceAtLeast(1)) }
                            }
                        },
                        label = { Text("Default sets") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                "timed" -> {
                    Spacer(Modifier.height(8.dp))
                    val mins = step.durationSeconds / 60
                    OutlinedTextField(
                        value = if (mins > 0) mins.toString() else (step.durationSeconds).toString(),
                        onValueChange = { text ->
                            text.toIntOrNull()?.let { s ->
                                onUpdate { it.copy(durationSeconds = (s * 60).coerceAtLeast(1)) }
                            }
                        },
                        label = { Text("Duration (minutes)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // Superset indicator
            if (step.supersetGroup != null) {
                Spacer(Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "In superset",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                    Spacer(Modifier.width(8.dp))
                    if (onRemoveSupersetGroup != null) {
                        TextButton(onClick = onRemoveSupersetGroup) {
                            Text("Ungroup", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

// ==================== Step 2: Schedule ====================

@Composable
private fun ScheduleStep(
    state: TaskEditorState,
    onSetRecurrenceType: (String?) -> Unit,
    onToggleWeeklyDay: (String) -> Unit,
    onSetBlockingCondition: (Boolean) -> Unit,
    onSetHomeOnlyBlocking: (Boolean) -> Unit,
    onSetAvailableFrom: (String) -> Unit,
    onSetDueAt: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Spacer(Modifier.height(4.dp))
            Text("Recurrence", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            val recurrenceOptions = listOf(
                null to "One-time",
                "daily" to "Daily",
                "weekly" to "Weekly",
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                recurrenceOptions.forEach { (value, label) ->
                    FilterChip(
                        selected = state.recurrenceType == value,
                        onClick = { onSetRecurrenceType(value) },
                        label = { Text(label) },
                    )
                }
            }
        }

        if (state.recurrenceType == "weekly") {
            item {
                Text("Days", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                val days = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")
                val dayLabels = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    days.forEachIndexed { index, day ->
                        FilterChip(
                            selected = day in state.weeklyDays,
                            onClick = { onToggleWeeklyDay(day) },
                            label = { Text(dayLabels[index], style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }
            }
        }

        item {
            Text("Availability Window", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                text = "When this task can be completed and when it becomes overdue",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = state.availableFrom,
                    onValueChange = onSetAvailableFrom,
                    label = { Text("Available from (HH:mm)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = state.dueAt,
                    onValueChange = onSetDueAt,
                    label = { Text("Due at (HH:mm)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        item {
            Spacer(Modifier.height(8.dp))
            Text("Blocking", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Use as blocking condition")
                    Text(
                        text = "Apps will be blocked until this task is completed",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.isBlockingCondition,
                    onCheckedChange = onSetBlockingCondition,
                )
            }
            if (state.isBlockingCondition) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Only block when at home")
                        Text(
                            text = "Skip this task's blocking when you're away from your designated home location (e.g. on vacation)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = state.homeOnlyBlocking,
                        onCheckedChange = onSetHomeOnlyBlocking,
                    )
                }
            }
        }

        item { Spacer(Modifier.height(48.dp)) }
    }
}

// ==================== Companion App Picker ====================

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
private fun LazyListScope.companionAppPickerSection(
    selected: Set<String>,
    installedApps: List<dev.brainfence.data.apps.InstalledApp>,
    onToggle: (String) -> Unit,
) {
    item(key = "companion_header") {
        Spacer(Modifier.height(12.dp))
        Text("Companion apps", style = MaterialTheme.typography.titleSmall)
        Text(
            text = "Time spent in these apps will count toward the meditation duration. Leave empty for in-app timer only.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (selected.isNotEmpty()) {
        item(key = "companion_selected") {
            Spacer(Modifier.height(8.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                selected.forEach { pkg ->
                    val app = installedApps.find { it.packageName == pkg }
                    InputChip(
                        selected = true,
                        onClick = { onToggle(pkg) },
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

    item(key = "companion_picker_search") {
        Spacer(Modifier.height(8.dp))
        var query by remember { mutableStateOf("") }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search apps") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        val filtered = installedApps.filter { app ->
            query.isBlank() ||
                app.label.contains(query, ignoreCase = true) ||
                app.packageName.contains(query, ignoreCase = true)
        }
        Column {
            filtered.forEach { app ->
                ListItem(
                    modifier = Modifier.clickable { onToggle(app.packageName) },
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
                            checked = app.packageName in selected,
                            onCheckedChange = { onToggle(app.packageName) },
                        )
                    },
                )
            }
        }
    }
}
