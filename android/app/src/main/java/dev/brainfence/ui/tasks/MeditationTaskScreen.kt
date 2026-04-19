package dev.brainfence.ui.tasks

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.brainfence.domain.model.Task
import dev.brainfence.service.MeditationConfig
import dev.brainfence.service.MeditationTimerState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeditationTaskScreen(
    task: Task,
    meditationConfig: MeditationConfig,
    timerState: MeditationTimerState?,
    onStartInApp: () -> Unit,
    onStartCompanion: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onBack: () -> Unit,
) {
    var showCancelDialog by remember { mutableStateOf(false) }

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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            when {
                task.completedToday -> CompletedState()
                timerState == null -> NotStartedState(
                    meditationConfig = meditationConfig,
                    onStartInApp = onStartInApp,
                    onStartCompanion = onStartCompanion,
                )
                timerState.method == "companion_app" -> CompanionTrackingState(
                    timerState = timerState,
                    onCancel = { showCancelDialog = true },
                )
                else -> InAppTimerState(
                    timerState = timerState,
                    onPause = onPause,
                    onResume = onResume,
                    onCancel = { showCancelDialog = true },
                )
            }
        }
    }

    if (showCancelDialog) {
        AlertDialog(
            onDismissRequest = { showCancelDialog = false },
            title = { Text("Cancel meditation?") },
            text = { Text("Your progress will be lost.") },
            confirmButton = {
                TextButton(onClick = {
                    showCancelDialog = false
                    onCancel()
                }) {
                    Text("Cancel Session", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCancelDialog = false }) {
                    Text("Keep Going")
                }
            },
        )
    }
}

@Composable
private fun CompletedState() {
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

@Composable
private fun NotStartedState(
    meditationConfig: MeditationConfig,
    onStartInApp: () -> Unit,
    onStartCompanion: () -> Unit,
) {
    MeditationRing(elapsed = 0, target = meditationConfig.durationSeconds)
    Spacer(Modifier.height(24.dp))
    Text(
        text = formatMeditationTime(meditationConfig.durationSeconds),
        style = MaterialTheme.typography.displayMedium,
        fontWeight = FontWeight.Light,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = "Meditation session",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (meditationConfig.bellIntervalSeconds > 0) {
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Bell every ${formatMeditationTime(meditationConfig.bellIntervalSeconds)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(32.dp))

    Button(onClick = onStartInApp) {
        Icon(Icons.Default.SelfImprovement, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text("Start Meditation")
    }

    if (meditationConfig.companionApps.isNotEmpty()) {
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onStartCompanion) {
            Text("Use Companion App")
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Detects meditation in ${meditationConfig.companionApps.joinToString { appLabel(it) }}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun InAppTimerState(
    timerState: MeditationTimerState,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
) {
    val elapsed = timerState.elapsedSeconds
    val remaining = (timerState.targetSeconds - elapsed).coerceAtLeast(0)

    // Navigated-away warning
    if (timerState.navigatedAway) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "Timer paused — you navigated away. Return focus to continue.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }

    MeditationRing(elapsed = elapsed, target = timerState.targetSeconds)
    Spacer(Modifier.height(24.dp))
    Text(
        text = formatMeditationTime(remaining),
        style = MaterialTheme.typography.displayMedium,
        fontWeight = FontWeight.Light,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = if (timerState.running) "Meditating" else "Paused",
        style = MaterialTheme.typography.bodyMedium,
        color = if (timerState.running)
            MaterialTheme.colorScheme.tertiary
        else
            MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = "${formatMeditationTime(elapsed)} elapsed of ${formatMeditationTime(timerState.targetSeconds)}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (timerState.pauses > 0) {
        Text(
            text = "${timerState.pauses} pause${if (timerState.pauses != 1) "s" else ""}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(32.dp))

    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        OutlinedButton(
            onClick = onCancel,
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            ),
        ) {
            Icon(Icons.Default.Close, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Cancel")
        }

        if (timerState.running) {
            FilledTonalButton(onClick = onPause) {
                Icon(Icons.Default.Pause, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Pause")
            }
        } else {
            Button(onClick = onResume) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Resume")
            }
        }
    }
}

@Composable
private fun CompanionTrackingState(
    timerState: MeditationTimerState,
    onCancel: () -> Unit,
) {
    val elapsed = timerState.elapsedSeconds
    val remaining = (timerState.targetSeconds - elapsed).coerceAtLeast(0)

    MeditationRing(elapsed = elapsed, target = timerState.targetSeconds)
    Spacer(Modifier.height(24.dp))
    Text(
        text = formatMeditationTime(remaining),
        style = MaterialTheme.typography.displayMedium,
        fontWeight = FontWeight.Light,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = if (timerState.running)
            "Tracking: ${appLabel(timerState.companionApp ?: "")}"
        else
            "Waiting for companion app",
        style = MaterialTheme.typography.bodyMedium,
        color = if (timerState.running)
            MaterialTheme.colorScheme.tertiary
        else
            MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = "${formatMeditationTime(elapsed)} accumulated of ${formatMeditationTime(timerState.targetSeconds)}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = "Open your meditation app — time accumulates automatically",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(32.dp))

    OutlinedButton(
        onClick = onCancel,
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.error,
        ),
    ) {
        Icon(Icons.Default.Close, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text("Cancel")
    }
}

@Composable
private fun MeditationRing(elapsed: Int, target: Int) {
    val progress = if (target > 0) (elapsed.toFloat() / target).coerceIn(0f, 1f) else 0f
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(durationMillis = 500, easing = LinearEasing),
        label = "meditation_progress",
    )
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val progressColor = MaterialTheme.colorScheme.tertiary

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(200.dp),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = 12.dp.toPx()
            val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
            val topLeft = Offset(strokeWidth / 2, strokeWidth / 2)

            drawArc(
                color = trackColor,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
            )

            drawArc(
                color = progressColor,
                startAngle = -90f,
                sweepAngle = animatedProgress * 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
            )
        }

        Icon(
            imageVector = Icons.Default.SelfImprovement,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp),
        )
    }
}

/** Extract a human-readable label from a package name. */
private fun appLabel(packageName: String): String {
    if (packageName.isBlank()) return "app"
    val parts = packageName.split(".")
    return parts.lastOrNull()?.replaceFirstChar { it.uppercase() } ?: packageName
}

private fun formatMeditationTime(totalSeconds: Int): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}
