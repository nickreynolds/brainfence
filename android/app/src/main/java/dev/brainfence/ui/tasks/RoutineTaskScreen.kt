package dev.brainfence.ui.tasks

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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.brainfence.domain.model.RoutineStep
import dev.brainfence.domain.model.Task
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutineTaskScreen(
    task: Task,
    steps: List<RoutineStep>,
    stepStates: Map<String, StepUiState>,
    isCompleting: Boolean,
    onToggleCheckbox: (stepId: String) -> Unit,
    onUpdateSet: (stepId: String, setIndex: Int, entry: StepSetEntry) -> Unit,
    onAddSet: (stepId: String) -> Unit,
    onRemoveSet: (stepId: String) -> Unit,
    onStartTimer: (stepId: String, setIndex: Int) -> Unit,
    onStopTimer: (stepId: String, setIndex: Int) -> Unit,
    onFinish: () -> Unit,
    onBack: () -> Unit,
) {
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

                itemsIndexed(steps, key = { _, step -> step.id }) { _, step ->
                    val state = stepStates[step.id] ?: return@itemsIndexed
                    StepCard(
                        state = state,
                        onToggleCheckbox = { onToggleCheckbox(step.id) },
                        onUpdateSet = { idx, entry -> onUpdateSet(step.id, idx, entry) },
                        onAddSet = { onAddSet(step.id) },
                        onRemoveSet = { onRemoveSet(step.id) },
                        onStartTimer = { idx -> onStartTimer(step.id, idx) },
                        onStopTimer = { idx -> onStopTimer(step.id, idx) },
                    )
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
                "weight_reps" -> WeightRepsStepContent(
                    sets = state.sets,
                    onUpdateSet = onUpdateSet,
                    onAddSet = onAddSet,
                    onRemoveSet = onRemoveSet,
                )
                "just_reps" -> JustRepsStepContent(
                    sets = state.sets,
                    onUpdateSet = onUpdateSet,
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

@Composable
private fun WeightRepsStepContent(
    sets: List<StepSetEntry>,
    onUpdateSet: (setIndex: Int, entry: StepSetEntry) -> Unit,
    onAddSet: () -> Unit,
    onRemoveSet: () -> Unit,
) {
    // Header row
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Set",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(36.dp),
        )
        Text(
            text = "Weight",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "Reps",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(40.dp)) // space for check
    }

    Spacer(Modifier.height(4.dp))

    sets.forEachIndexed { index, entry ->
        SetRow(
            setNumber = index + 1,
            entry = entry,
            showWeight = true,
            onUpdate = { onUpdateSet(index, it) },
        )
        Spacer(Modifier.height(4.dp))
    }

    SetControlButtons(
        setCount = sets.size,
        onAddSet = onAddSet,
        onRemoveSet = onRemoveSet,
    )
}

@Composable
private fun JustRepsStepContent(
    sets: List<StepSetEntry>,
    onUpdateSet: (setIndex: Int, entry: StepSetEntry) -> Unit,
    onAddSet: () -> Unit,
    onRemoveSet: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Set",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(36.dp),
        )
        Text(
            text = "Reps",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(40.dp))
    }

    Spacer(Modifier.height(4.dp))

    sets.forEachIndexed { index, entry ->
        SetRow(
            setNumber = index + 1,
            entry = entry,
            showWeight = false,
            onUpdate = { onUpdateSet(index, it) },
        )
        Spacer(Modifier.height(4.dp))
    }

    SetControlButtons(
        setCount = sets.size,
        onAddSet = onAddSet,
        onRemoveSet = onRemoveSet,
    )
}

@Composable
private fun SetRow(
    setNumber: Int,
    entry: StepSetEntry,
    showWeight: Boolean,
    onUpdate: (StepSetEntry) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$setNumber",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(36.dp),
        )

        if (showWeight) {
            OutlinedTextField(
                value = entry.weight?.let { if (it == it.toLong().toDouble()) it.toLong().toString() else it.toString() } ?: "",
                onValueChange = { text ->
                    val w = text.toDoubleOrNull()
                    onUpdate(entry.copy(weight = w))
                },
                label = { Text("lbs") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
        }

        OutlinedTextField(
            value = entry.reps?.toString() ?: "",
            onValueChange = { text ->
                val r = text.toIntOrNull()
                onUpdate(entry.copy(reps = r))
            },
            label = { Text("reps") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.weight(1f),
        )

        IconButton(
            onClick = { onUpdate(entry.copy(completed = !entry.completed)) },
        ) {
            Icon(
                imageVector = if (entry.completed) Icons.Default.CheckCircle else Icons.Outlined.Circle,
                contentDescription = if (entry.completed) "Completed" else "Mark complete",
                tint = if (entry.completed) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.outline,
            )
        }
    }
}

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

private fun formatTimerTime(totalSeconds: Int): String {
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
