package dev.brainfence.ui.tasks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.brainfence.domain.model.RoutineStep
import dev.brainfence.domain.model.Task
import org.json.JSONObject

private val WEIGHT_OPTIONS: List<Double> = (0..200).map { it * 2.5 }

private fun formatWeight(weight: Double): String =
    if (weight == weight.toLong().toDouble()) weight.toLong().toString() else weight.toString()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutineTaskScreen(
    task: Task,
    steps: List<RoutineStep>,
    stepStates: Map<String, StepUiState>,
    supersetRounds: Map<String, SupersetRoundState> = emptyMap(),
    isCompleting: Boolean,
    onToggleCheckbox: (stepId: String) -> Unit,
    onUpdateSet: (stepId: String, setIndex: Int, entry: StepSetEntry) -> Unit,
    onCompleteCurrentSet: (stepId: String) -> Unit,
    onGoToSet: (stepId: String, setIndex: Int) -> Unit,
    onAddSet: (stepId: String) -> Unit,
    onRemoveSet: (stepId: String) -> Unit,
    onStartTimer: (stepId: String, setIndex: Int) -> Unit,
    onStopTimer: (stepId: String, setIndex: Int) -> Unit,
    onAdvanceRound: (groupId: String) -> Unit = {},
    onGoToRound: (groupId: String, round: Int) -> Unit = { _, _ -> },
    onAddStep: (title: String, stepType: String, defaultSets: Int, durationSeconds: Int) -> Unit,
    onFinish: () -> Unit,
    onBack: () -> Unit,
) {
    var showAddStepDialog by remember { mutableStateOf(false) }

    if (showAddStepDialog) {
        AddStepDialog(
            isWorkout = task.taskType == "workout",
            onDismiss = { showAddStepDialog = false },
            onAdd = { title, stepType, defaultSets, durationSeconds ->
                onAddStep(title, stepType, defaultSets, durationSeconds)
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(task.title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (task.completedToday) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(80.dp),
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "Completed today",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { Spacer(Modifier.height(4.dp)) }

                // Build display items: individual steps + superset groups
                val renderedSupersets = mutableSetOf<String>()
                val displayItems = mutableListOf<Any>()
                for (step in steps) {
                    val group = step.supersetGroup
                    if (group != null && group !in renderedSupersets) {
                        renderedSupersets.add(group)
                        val groupSteps = steps.filter { it.supersetGroup == group }
                        displayItems.add(SupersetGroup(group, groupSteps))
                    } else if (group == null) {
                        displayItems.add(step)
                    }
                }

                itemsIndexed(displayItems, key = { _, item ->
                    when (item) {
                        is RoutineStep -> item.id
                        is SupersetGroup -> "superset-${item.groupId}"
                        else -> ""
                    }
                }) { _, item ->
                    when (item) {
                        is RoutineStep -> {
                            val state = stepStates[item.id] ?: return@itemsIndexed
                            StepCard(
                                state = state,
                                onToggleCheckbox = { onToggleCheckbox(item.id) },
                                onUpdateSet = { idx, entry -> onUpdateSet(item.id, idx, entry) },
                                onCompleteCurrentSet = { onCompleteCurrentSet(item.id) },
                                onGoToSet = { idx -> onGoToSet(item.id, idx) },
                                onAddSet = { onAddSet(item.id) },
                                onRemoveSet = { onRemoveSet(item.id) },
                                onStartTimer = { idx -> onStartTimer(item.id, idx) },
                                onStopTimer = { idx -> onStopTimer(item.id, idx) },
                            )
                        }
                        is SupersetGroup -> {
                            val roundState = supersetRounds[item.groupId]
                            SupersetCard(
                                group = item,
                                stepStates = stepStates,
                                roundState = roundState,
                                onToggleCheckbox = onToggleCheckbox,
                                onUpdateSet = onUpdateSet,
                                onStartTimer = onStartTimer,
                                onStopTimer = onStopTimer,
                                onAdvanceRound = { onAdvanceRound(item.groupId) },
                                onGoToRound = { round -> onGoToRound(item.groupId, round) },
                            )
                        }
                    }
                }

                item {
                    Spacer(Modifier.height(4.dp))
                    OutlinedButton(
                        onClick = { showAddStepDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (task.taskType == "workout") "Add Exercise" else "Add Step")
                    }
                }

                item {
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = onFinish,
                        enabled = !isCompleting,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (isCompleting) "Saving..." else "Finish Routine")
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun StepCard(
    state: StepUiState,
    onToggleCheckbox: () -> Unit,
    onUpdateSet: (setIndex: Int, entry: StepSetEntry) -> Unit,
    onCompleteCurrentSet: () -> Unit,
    onGoToSet: (Int) -> Unit,
    onAddSet: () -> Unit,
    onRemoveSet: () -> Unit,
    onStartTimer: (setIndex: Int) -> Unit,
    onStopTimer: (setIndex: Int) -> Unit,
) {
    val step = state.step
    val allSetsCompleted = state.sets.all { it.completed }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (allSetsCompleted)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            else
                MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Step header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (allSetsCompleted) Icons.Default.CheckCircle else Icons.Outlined.Circle,
                    contentDescription = null,
                    tint = if (allSetsCompleted) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = step.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                )
            }

            Spacer(Modifier.height(12.dp))

            when (step.stepType) {
                "checkbox" -> CheckboxStepContent(
                    entry = state.sets.first(),
                    onToggle = onToggleCheckbox,
                )
                "weight_reps" -> SetsStepContent(
                    sets = state.sets,
                    activeSetIndex = state.activeSetIndex,
                    showWeight = true,
                    onUpdateSet = onUpdateSet,
                    onCompleteSet = onCompleteCurrentSet,
                    onGoToSet = onGoToSet,
                    onAddSet = onAddSet,
                    onRemoveSet = onRemoveSet,
                )
                "just_reps" -> SetsStepContent(
                    sets = state.sets,
                    activeSetIndex = state.activeSetIndex,
                    showWeight = false,
                    onUpdateSet = onUpdateSet,
                    onCompleteSet = onCompleteCurrentSet,
                    onGoToSet = onGoToSet,
                    onAddSet = onAddSet,
                    onRemoveSet = onRemoveSet,
                )
                "timed" -> TimedStepContent(
                    entry = state.sets.first(),
                    config = JSONObject(step.config),
                    onStart = { onStartTimer(0) },
                    onStop = { onStopTimer(0) },
                )
            }
        }
    }
}

@Composable
private fun CheckboxStepContent(
    entry: StepSetEntry,
    onToggle: () -> Unit,
) {
    FilledTonalButton(
        onClick = onToggle,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(
            imageVector = if (entry.completed) Icons.Default.CheckCircle else Icons.Outlined.Circle,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(if (entry.completed) "Done" else "Mark Complete")
    }
}

// ==================== Sets Step Content (weight_reps / just_reps) ====================

@Composable
private fun SetsStepContent(
    sets: List<StepSetEntry>,
    activeSetIndex: Int,
    showWeight: Boolean,
    onUpdateSet: (setIndex: Int, entry: StepSetEntry) -> Unit,
    onCompleteSet: () -> Unit,
    onGoToSet: (Int) -> Unit,
    onAddSet: () -> Unit,
    onRemoveSet: () -> Unit,
) {
    val allCompleted = sets.all { it.completed }

    if (allCompleted) {
        // All sets done — show all as tappable summaries
        sets.forEachIndexed { index, entry ->
            CompletedSetSummary(
                setNumber = index + 1,
                entry = entry,
                showWeight = showWeight,
                onClick = { onGoToSet(index) },
            )
        }
    } else {
        // Show completed sets (not the active one) as summaries
        sets.forEachIndexed { index, entry ->
            if (entry.completed && index != activeSetIndex) {
                CompletedSetSummary(
                    setNumber = index + 1,
                    entry = entry,
                    showWeight = showWeight,
                    onClick = { onGoToSet(index) },
                )
            }
        }

        // Set indicator
        if (sets.size > 1) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Set ${activeSetIndex + 1} of ${sets.size}",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(8.dp))
        }

        // Active set inputs
        val activeEntry = sets.getOrNull(activeSetIndex) ?: return

        if (showWeight) {
            WeightStepperPicker(
                weight = activeEntry.weight ?: 0.0,
                onWeightChange = { weight ->
                    onUpdateSet(activeSetIndex, activeEntry.copy(weight = weight))
                },
            )
            Spacer(Modifier.height(12.dp))
        }

        RepsStepperPicker(
            reps = activeEntry.reps ?: 0,
            onRepsChange = { reps ->
                onUpdateSet(activeSetIndex, activeEntry.copy(reps = reps))
            },
        )

        Spacer(Modifier.height(12.dp))

        if (activeEntry.completed) {
            // Viewing a completed set (user tapped back) — allow undo
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                TextButton(onClick = {
                    onUpdateSet(activeSetIndex, activeEntry.copy(completed = false))
                }) {
                    Text("Undo Complete")
                }
            }
        } else {
            Button(
                onClick = onCompleteSet,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Complete Set")
            }
        }
    }

    Spacer(Modifier.height(8.dp))

    SetControlButtons(
        setCount = sets.size,
        onAddSet = onAddSet,
        onRemoveSet = onRemoveSet,
    )
}

@Composable
private fun CompletedSetSummary(
    setNumber: Int,
    entry: StepSetEntry,
    showWeight: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = buildString {
                append("Set $setNumber: ")
                if (showWeight && entry.weight != null) {
                    append(formatWeight(entry.weight))
                    append(" lbs \u00D7 ")
                }
                append("${entry.reps ?: 0} reps")
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ==================== Stepper Pickers ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WeightStepperPicker(
    weight: Double,
    onWeightChange: (Double) -> Unit,
) {
    val currentIndex = WEIGHT_OPTIONS.indexOf(weight).takeIf { it >= 0 }
        ?: WEIGHT_OPTIONS.indexOfFirst { it >= weight }.takeIf { it >= 0 }
        ?: 0

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        FilledTonalIconButton(
            onClick = { if (currentIndex > 0) onWeightChange(WEIGHT_OPTIONS[currentIndex - 1]) },
            enabled = currentIndex > 0,
        ) {
            Icon(Icons.Default.Remove, contentDescription = "Decrease weight")
        }

        Spacer(Modifier.width(8.dp))

        var expanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
            modifier = Modifier.weight(1f),
        ) {
            OutlinedTextField(
                value = "${formatWeight(weight)} lbs",
                onValueChange = {},
                readOnly = true,
                label = { Text("Weight") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
                singleLine = true,
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                WEIGHT_OPTIONS.forEach { option ->
                    DropdownMenuItem(
                        text = { Text("${formatWeight(option)} lbs") },
                        onClick = {
                            onWeightChange(option)
                            expanded = false
                        },
                    )
                }
            }
        }

        Spacer(Modifier.width(8.dp))

        FilledTonalIconButton(
            onClick = {
                if (currentIndex < WEIGHT_OPTIONS.size - 1) onWeightChange(WEIGHT_OPTIONS[currentIndex + 1])
            },
            enabled = currentIndex < WEIGHT_OPTIONS.size - 1,
        ) {
            Icon(Icons.Default.Add, contentDescription = "Increase weight")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RepsStepperPicker(
    reps: Int,
    onRepsChange: (Int) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        FilledTonalIconButton(
            onClick = { if (reps > 0) onRepsChange(reps - 1) },
            enabled = reps > 0,
        ) {
            Icon(Icons.Default.Remove, contentDescription = "Decrease reps")
        }

        Spacer(Modifier.width(8.dp))

        var expanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
            modifier = Modifier.weight(1f),
        ) {
            OutlinedTextField(
                value = "$reps",
                onValueChange = {},
                readOnly = true,
                label = { Text("Reps") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
                singleLine = true,
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                (0..100).forEach { option ->
                    DropdownMenuItem(
                        text = { Text("$option") },
                        onClick = {
                            onRepsChange(option)
                            expanded = false
                        },
                    )
                }
            }
        }

        Spacer(Modifier.width(8.dp))

        FilledTonalIconButton(
            onClick = { if (reps < 100) onRepsChange(reps + 1) },
            enabled = reps < 100,
        ) {
            Icon(Icons.Default.Add, contentDescription = "Increase reps")
        }
    }
}

// ==================== Set Control Buttons ====================

@Composable
private fun SetControlButtons(
    setCount: Int,
    onAddSet: () -> Unit,
    onRemoveSet: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        TextButton(onClick = onRemoveSet, enabled = setCount > 1) {
            Icon(Icons.Default.Remove, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("Remove Set")
        }
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = onAddSet) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("Add Set")
        }
    }
}

// ==================== Timer ====================

@Composable
private fun TimedStepContent(
    entry: StepSetEntry,
    config: JSONObject,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val targetSeconds = config.optInt("duration_seconds", 60)
    val remaining = (targetSeconds - entry.timerElapsed).coerceAtLeast(0)

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = formatTimerTime(if (entry.completed) 0 else remaining),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Light,
            color = when {
                entry.completed -> MaterialTheme.colorScheme.primary
                entry.timerRunning -> MaterialTheme.colorScheme.tertiary
                else -> MaterialTheme.colorScheme.onSurface
            },
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = when {
                entry.completed -> "Completed"
                entry.timerRunning -> "Running"
                entry.timerElapsed > 0 -> "Stopped"
                else -> formatTimerTime(targetSeconds) + " required"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))

        if (!entry.completed) {
            if (entry.timerRunning) {
                FilledTonalButton(onClick = onStop) {
                    Icon(Icons.Default.Stop, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Stop")
                }
            } else {
                FilledTonalButton(onClick = onStart) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (entry.timerElapsed > 0) "Restart" else "Start")
                }
            }
        }
    }
}

// ==================== Superset ====================

private data class SupersetGroup(
    val groupId: String,
    val steps: List<RoutineStep>,
)

@Composable
private fun SupersetCard(
    group: SupersetGroup,
    stepStates: Map<String, StepUiState>,
    roundState: SupersetRoundState?,
    onToggleCheckbox: (stepId: String) -> Unit,
    onUpdateSet: (stepId: String, setIndex: Int, entry: StepSetEntry) -> Unit,
    onStartTimer: (stepId: String, setIndex: Int) -> Unit,
    onStopTimer: (stepId: String, setIndex: Int) -> Unit,
    onAdvanceRound: () -> Unit,
    onGoToRound: (Int) -> Unit,
) {
    val currentRound = roundState?.currentRound ?: 1
    val totalRounds = roundState?.totalRounds ?: 1
    val setIndex = currentRound - 1

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.2f),
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Superset header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Superset",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.tertiary,
                )
                Text(
                    text = "Round $currentRound of $totalRounds",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }

            Spacer(Modifier.height(12.dp))

            // Render each step in the superset, showing only the current round's set
            group.steps.forEach { step ->
                val state = stepStates[step.id] ?: return@forEach
                val currentEntry = state.sets.getOrNull(setIndex) ?: return@forEach

                SupersetStepRow(
                    step = step,
                    entry = currentEntry,
                    setIndex = setIndex,
                    onToggleCheckbox = { onToggleCheckbox(step.id) },
                    onUpdateSet = { entry -> onUpdateSet(step.id, setIndex, entry) },
                    onStartTimer = { onStartTimer(step.id, setIndex) },
                    onStopTimer = { onStopTimer(step.id, setIndex) },
                )

                if (step != group.steps.last()) {
                    Spacer(Modifier.height(8.dp))
                }
            }

            Spacer(Modifier.height(12.dp))

            // Round indicator chips
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                (1..totalRounds).forEach { round ->
                    val isComplete = group.steps.all { step ->
                        val state = stepStates[step.id]
                        state?.sets?.getOrNull(round - 1)?.completed == true
                    }
                    FilterChip(
                        selected = round == currentRound,
                        onClick = { onGoToRound(round) },
                        label = { Text("$round") },
                        leadingIcon = if (isComplete) {
                            {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        } else null,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }

            // Next round button
            if (currentRound < totalRounds) {
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onAdvanceRound,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Next Round")
                }
            }
        }
    }
}

@Composable
private fun SupersetStepRow(
    step: RoutineStep,
    entry: StepSetEntry,
    setIndex: Int,
    onToggleCheckbox: () -> Unit,
    onUpdateSet: (StepSetEntry) -> Unit,
    onStartTimer: () -> Unit,
    onStopTimer: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (entry.completed)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            else
                MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (entry.completed) Icons.Default.CheckCircle else Icons.Outlined.Circle,
                    contentDescription = null,
                    tint = if (entry.completed) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = step.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                )
            }
            Spacer(Modifier.height(8.dp))

            when (step.stepType) {
                "checkbox" -> {
                    FilledTonalButton(
                        onClick = onToggleCheckbox,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (entry.completed) "Done" else "Mark Complete")
                    }
                }
                "weight_reps" -> {
                    WeightStepperPicker(
                        weight = entry.weight ?: 0.0,
                        onWeightChange = { onUpdateSet(entry.copy(weight = it)) },
                    )
                    Spacer(Modifier.height(8.dp))
                    RepsStepperPicker(
                        reps = entry.reps ?: 0,
                        onRepsChange = { onUpdateSet(entry.copy(reps = it)) },
                    )
                    Spacer(Modifier.height(8.dp))
                    FilledTonalButton(
                        onClick = { onUpdateSet(entry.copy(completed = !entry.completed)) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            imageVector = if (entry.completed) Icons.Default.CheckCircle
                                          else Icons.Outlined.Circle,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(if (entry.completed) "Done" else "Complete")
                    }
                }
                "just_reps" -> {
                    RepsStepperPicker(
                        reps = entry.reps ?: 0,
                        onRepsChange = { onUpdateSet(entry.copy(reps = it)) },
                    )
                    Spacer(Modifier.height(8.dp))
                    FilledTonalButton(
                        onClick = { onUpdateSet(entry.copy(completed = !entry.completed)) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            imageVector = if (entry.completed) Icons.Default.CheckCircle
                                          else Icons.Outlined.Circle,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(if (entry.completed) "Done" else "Complete")
                    }
                }
                "timed" -> {
                    val config = JSONObject(step.config)
                    TimedStepContent(
                        entry = entry,
                        config = config,
                        onStart = onStartTimer,
                        onStop = onStopTimer,
                    )
                }
            }
        }
    }
}

// ==================== Add Step Dialog ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddStepDialog(
    isWorkout: Boolean,
    onDismiss: () -> Unit,
    onAdd: (title: String, stepType: String, defaultSets: Int, durationSeconds: Int) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var stepType by remember { mutableStateOf(if (isWorkout) "weight_reps" else "checkbox") }
    var defaultSets by remember { mutableStateOf(3) }
    var durationSeconds by remember { mutableStateOf(60) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isWorkout) "Add Exercise" else "Add Step") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(if (isWorkout) "Exercise name" else "Step name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                // Type dropdown
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
                val selectedLabel = stepTypes.find { it.first == stepType }?.second ?: stepType

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
                                    stepType = value
                                    typeExpanded = false
                                },
                            )
                        }
                    }
                }

                when (stepType) {
                    "weight_reps", "just_reps" -> {
                        OutlinedTextField(
                            value = defaultSets.toString(),
                            onValueChange = { text ->
                                text.toIntOrNull()?.let { defaultSets = it.coerceAtLeast(1) }
                            },
                            label = { Text("Default sets") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    "timed" -> {
                        val mins = durationSeconds / 60
                        OutlinedTextField(
                            value = if (mins > 0) mins.toString() else durationSeconds.toString(),
                            onValueChange = { text ->
                                text.toIntOrNull()?.let { durationSeconds = (it * 60).coerceAtLeast(1) }
                            },
                            label = { Text("Duration (minutes)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (title.isNotBlank()) {
                        onAdd(title, stepType, defaultSets, durationSeconds)
                        onDismiss()
                    }
                },
                enabled = title.isNotBlank(),
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

// ==================== Utilities ====================

private fun formatTimerTime(totalSeconds: Int): String {
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
