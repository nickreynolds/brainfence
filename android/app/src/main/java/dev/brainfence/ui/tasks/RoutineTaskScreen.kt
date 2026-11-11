package dev.brainfence.ui.tasks

import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.filled.Place
import dev.brainfence.domain.recurrence.status
import dev.brainfence.domain.verification.CompletionAlternatives
import dev.brainfence.domain.verification.TimedSetsConfig
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.style.TextOverflow
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.brainfence.domain.model.Report
import dev.brainfence.domain.model.ReportSubmission
import dev.brainfence.domain.model.RoutineStep
import dev.brainfence.ui.report.ReportForm
import dev.brainfence.ui.report.ReportSubmissionSummary
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
    allStepsCompleted: Boolean = false,
    isAutoRoutine: Boolean = false,
    autoProgress: AutoRoutineProgress = AutoRoutineProgress(),
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
    onRemoveStep: (stepId: String) -> Unit,
    onStartAutoRoutine: () -> Unit = {},
    onStopAutoRoutine: () -> Unit = {},
    onSetTimedWeight: (stepId: String, setIndex: Int, weight: Double?) -> Unit = { _, _, _ -> },
    onConfirmSetWeight: () -> Unit = {},
    reportSteps: Map<String, Report?> = emptyMap(),
    reportSubmissions: Map<String, ReportSubmission> = emptyMap(),
    submittingReportSteps: Set<String> = emptySet(),
    onSubmitReport: (stepId: String, answers: Map<String, String>, note: String) -> Unit = { _, _, _ -> },
    onFinish: () -> Unit,
    onBack: () -> Unit,
) {
    val isRoutine = task.taskType == "routine"
    val isDone = task.status().completed

    // Keep screen on while routine is in progress
    if (!isDone) {
        val view = LocalView.current
        DisposableEffect(Unit) {
            view.keepScreenOn = true
            onDispose { view.keepScreenOn = false }
        }
    }

    // Auto-complete routine tasks when all steps are done
    if (isRoutine) {
        LaunchedEffect(allStepsCompleted) {
            if (allStepsCompleted && !isCompleting) {
                onFinish()
            }
        }
    }

    // Auto-complete when auto-routine finishes
    if (isAutoRoutine) {
        LaunchedEffect(autoProgress.phase) {
            if (autoProgress.phase == AutoPhase.COMPLETED && !isCompleting) {
                onFinish()
            }
        }
    }

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
        if (isDone) {
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
        } else if (isAutoRoutine) {
            AutoRoutineContent(
                task = task,
                steps = steps,
                stepStates = stepStates,
                autoProgress = autoProgress,
                isCompleting = isCompleting,
                onStart = onStartAutoRoutine,
                onStop = onStopAutoRoutine,
                onSetWeight = onSetTimedWeight,
                onConfirmSetWeight = onConfirmSetWeight,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { Spacer(Modifier.height(4.dp)) }
                item { AlternativesNote(task) }

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
                                onRemoveStep = { onRemoveStep(item.id) },
                                report = reportSteps[item.id],
                                reportSubmission = reportSubmissions[item.id],
                                isSubmittingReport = item.id in submittingReportSteps,
                                onSubmitReport = { answers, note -> onSubmitReport(item.id, answers, note) },
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

                if (!isRoutine) {
                    item {
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = onFinish,
                            enabled = !isCompleting,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (isCompleting) "Saving..." else "Finish Workout")
                        }
                        Spacer(Modifier.height(24.dp))
                    }
                } else {
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
}

// ==================== Auto Routine ====================

@Composable
private fun AutoRoutineContent(
    task: Task,
    steps: List<RoutineStep>,
    stepStates: Map<String, StepUiState>,
    autoProgress: AutoRoutineProgress,
    isCompleting: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onSetWeight: (stepId: String, setIndex: Int, weight: Double?) -> Unit,
    onConfirmSetWeight: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val phase = autoProgress.phase
    val currentStep = steps.getOrNull(autoProgress.exerciseIndex)
    val currentConfig = currentStep?.let { TimedSetsConfig.parse(it.config) }
    val setIndex = currentConfig?.setOf(autoProgress.rep) ?: 0
    val hangInSide = currentConfig?.let { autoProgress.rep % it.reps + 1 } ?: (autoProgress.rep + 1)
    val side = currentConfig?.takeIf { it.sides == 2 }?.let { if (it.sideOf(autoProgress.rep) == 0) "Left hand" else "Right hand" }
    var confirming by remember { mutableStateOf(false) }

    // Compute overall progress
    val totalWork = steps.sumOf { TimedSetsConfig.parse(it.config).hangCount }
    val completedWork = steps.take(autoProgress.exerciseIndex).sumOf { TimedSetsConfig.parse(it.config).hangCount } + autoProgress.rep
    val overallProgress = if (totalWork > 0) completedWork.toFloat() / totalWork else 0f

    fun weightOf(step: RoutineStep, set: Int): Double? {
        val config = TimedSetsConfig.parse(step.config)
        return stepStates[step.id]?.sets?.getOrNull(set * config.hangsPerSet)?.weight
    }
    // Steps whose weight is confirmed once, before the routine starts.
    val startWeighted = steps.filter { TimedSetsConfig.parse(it.config).let { c -> c.weight != null && c.weightPrompt == "start" } }

    val phaseColor by animateColorAsState(
        targetValue = when (phase) {
            AutoPhase.COUNTDOWN -> MaterialTheme.colorScheme.tertiary
            AutoPhase.WORK -> MaterialTheme.colorScheme.primary
            AutoPhase.REST -> MaterialTheme.colorScheme.secondary
            else -> MaterialTheme.colorScheme.onSurface
        },
        animationSpec = tween(300),
        label = "phaseColor",
    )

    if (phase == AutoPhase.IDLE && confirming) {
        LazyColumn(
            modifier = modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Spacer(Modifier.height(4.dp))
                Text("Confirm weights", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Added weight for each hang (negative = assisted). Pre-filled from last time.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(startWeighted, key = { it.id }) { step ->
                val config = TimedSetsConfig.parse(step.config)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(step.title, style = MaterialTheme.typography.titleSmall)
                    WeightField(config.weight!!, weightOf(step, 0)) { onSetWeight(step.id, 0, it) }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { confirming = false }) { Text("Back") }
                    Button(onClick = { confirming = false; onStart() }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Confirm and start")
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
        return
    }

    if (phase == AutoPhase.IDLE) {
        LazyColumn(
            modifier = modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Spacer(Modifier.height(4.dp)) }
            item { AlternativesNote(task) }
            itemsIndexed(steps, key = { _, step -> step.id }) { index, step ->
                val config = TimedSetsConfig.parse(step.config)
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${index + 1}. ${step.title}", style = MaterialTheme.typography.titleMedium)
                        Text(
                            describeTimedSets(config),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        step.description?.takeIf { it.isNotBlank() }?.let { StepNotes(it) }
                        step.imageUrl?.let { StepImage(it, maxHeight = 160.dp) }
                        if (config.weight != null) {
                            Text(
                                if (config.weightPrompt == "each_set") "Weight asked before each set"
                                else "Last time: ${TimedSetsConfig.formatWeight(config.weight, weightOf(step, 0))}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            item {
                Button(
                    onClick = { if (startWeighted.isNotEmpty()) confirming = true else onStart() },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Start Routine", style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.height(24.dp))
            }
        }
        return
    }

    Column(
        modifier = modifier.padding(24.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (phase) {
            AutoPhase.WEIGHT -> {
                Text(
                    text = "Exercise ${autoProgress.exerciseIndex + 1} of ${steps.size}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = currentStep?.title ?: "",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = "Set ${setIndex + 1} of ${currentConfig?.sets ?: 1}" +
                        if (currentConfig?.sides == 2) " \u00b7 left, then right, same weight" else "",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                currentStep?.imageUrl?.let {
                    Spacer(Modifier.height(8.dp))
                    StepImage(it, maxHeight = 180.dp)
                }
                Spacer(Modifier.height(16.dp))
                if (currentStep != null && currentConfig?.weight != null) {
                    WeightField(currentConfig.weight, weightOf(currentStep, setIndex)) {
                        onSetWeight(currentStep.id, setIndex, it)
                    }
                }
                Spacer(Modifier.height(16.dp))
                Button(onClick = onConfirmSetWeight, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Start set", style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) { Text("Pause") }
            }

            AutoPhase.COUNTDOWN, AutoPhase.WORK, AutoPhase.REST, AutoPhase.PAUSED -> {
                val paused = phase == AutoPhase.PAUSED
                Text(
                    text = "Exercise ${autoProgress.exerciseIndex + 1} of ${steps.size}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                // "UP NEXT" while resting before a new set or exercise
                if ((phase == AutoPhase.REST || phase == AutoPhase.COUNTDOWN) && currentConfig?.startsSet(autoProgress.rep) == true) {
                    Text(
                        text = "UP NEXT",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.tertiary,
                        letterSpacing = 2.sp,
                    )
                    Spacer(Modifier.height(2.dp))
                }
                Text(
                    text = currentStep?.title ?: "",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = buildString {
                        if (currentConfig != null && currentConfig.sets > 1) append("Set ${setIndex + 1} of ${currentConfig.sets} \u00b7 ")
                        if (side != null) append("$side \u00b7 ")
                        append("Rep $hangInSide of ${currentConfig?.reps ?: 1}")
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (currentStep != null && currentConfig?.weight != null) {
                    Text(
                        TimedSetsConfig.formatWeight(currentConfig.weight, weightOf(currentStep, setIndex)),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                currentStep?.imageUrl?.let {
                    Spacer(Modifier.height(8.dp))
                    StepImage(it, maxHeight = 180.dp)
                }

                Spacer(Modifier.height(24.dp))

                Text(
                    text = when (phase) {
                        AutoPhase.COUNTDOWN -> "GET READY"
                        AutoPhase.WORK -> "GO"
                        AutoPhase.REST -> "REST"
                        else -> "PAUSED"
                    },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (paused) MaterialTheme.colorScheme.onSurfaceVariant else phaseColor,
                    letterSpacing = 2.sp,
                )
                if (!paused) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = autoProgress.secondsRemaining.toString(),
                        style = MaterialTheme.typography.displayLarge.copy(fontSize = 96.sp),
                        fontWeight = FontWeight.Light,
                        color = phaseColor,
                    )
                }

                Spacer(Modifier.height(24.dp))

                LinearProgressIndicator(
                    progress = { overallProgress },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                )
                Spacer(Modifier.height(16.dp))

                if (paused) {
                    Button(
                        onClick = onStart,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Resume Routine", style = MaterialTheme.typography.titleMedium)
                    }
                } else {
                    OutlinedButton(
                        onClick = onStop,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.Pause, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Pause")
                    }
                }
            }

            AutoPhase.COMPLETED -> {
                Spacer(Modifier.height(120.dp))
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(80.dp),
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = if (isCompleting) "Saving..." else "Routine Complete!",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            AutoPhase.IDLE -> Unit
        }
    }
}

private fun describeTimedSets(c: TimedSetsConfig): String {
    val hangs = "${c.reps}\u00d7 ${c.workSeconds}s on / ${c.restSeconds}s off" +
        if (c.sides == 2) " per hand (left, then right)" else ""
    if (c.sets <= 1) return hangs
    return "${c.sets} sets of $hangs \u00b7 ${formatTimerTime(c.setRestSeconds)} between sets"
}

/** Added weight (negative = assisted) or the load lifted, in lb. Blank = bodyweight / none. */
@Composable
private fun WeightField(kind: String, value: Double?, onChange: (Double?) -> Unit) {
    var text by remember { mutableStateOf(value?.let(::formatWeight) ?: "") }
    LaunchedEffect(value) {
        if (text.toDoubleOrNull() != value) text = value?.let(::formatWeight) ?: ""
    }
    val allowNegative = kind == "added"
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            val ok = input.isEmpty() || (allowNegative && input == "-") ||
                input.toDoubleOrNull()?.let { allowNegative || it >= 0 } == true
            if (ok) {
                text = input
                when {
                    input.isEmpty() -> onChange(null)
                    input.toDoubleOrNull() != null -> onChange(input.toDouble())
                }
            }
        },
        label = { Text(if (allowNegative) "Added weight (lb, \u2212 = assisted)" else "Weight (lb)") },
        placeholder = { Text(if (allowNegative) "Bodyweight" else "0") },
        singleLine = true,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = if (allowNegative) androidx.compose.ui.text.input.KeyboardType.Text
            else androidx.compose.ui.text.input.KeyboardType.Decimal,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** A step's picture (e.g. which holds to use), scaled to fit and cached by Coil. */
@Composable
internal fun StepImage(url: String, maxHeight: androidx.compose.ui.unit.Dp = 220.dp) {
    if (!url.startsWith("https://")) return
    coil.compose.AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .clip(RoundedCornerShape(12.dp)),
    )
}

/** "Or be at Climbing gym for 30 min": the task's other ways to complete (shared/CONTRACTS.md). */
@Composable
internal fun AlternativesNote(task: Task) {
    val alternatives = remember(task.completionAlternatives) { CompletionAlternatives.parseGps(task.completionAlternatives) }
    if (alternatives.isEmpty()) return
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.Place,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                "Or ${alternatives.joinToString(", or ") { it.describe() }}: this completes automatically from your location.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
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
    onRemoveStep: () -> Unit,
    report: Report?,
    reportSubmission: ReportSubmission?,
    isSubmittingReport: Boolean,
    onSubmitReport: (answers: Map<String, String>, note: String) -> Unit,
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
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = onRemoveStep,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Remove step",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (!step.description.isNullOrBlank()) {
                StepNotes(step.description)
            }
            step.imageUrl?.let {
                Spacer(Modifier.height(8.dp))
                StepImage(it)
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
                "report" -> when {
                    reportSubmission != null -> ReportSubmissionSummary(reportSubmission)
                    report == null -> Text(
                        text = "This step's report can't be found. Edit the routine to choose one.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> ReportForm(
                        report = report,
                        isSubmitting = isSubmittingReport,
                        onSubmit = onSubmitReport,
                    )
                }
            }
        }
    }
}

/** A step's notes: two lines, tap to expand. */
@Composable
private fun StepNotes(text: String) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = if (expanded) Int.MAX_VALUE else 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 28.dp, top = 4.dp)
            .clickable { expanded = !expanded },
    )
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
