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
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.unit.dp
import dev.brainfence.domain.model.Task
import dev.brainfence.service.DurationConfig
import dev.brainfence.service.DurationTimerState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DurationTaskScreen(
    task: Task,
    durationConfig: DurationConfig,
    timerState: DurationTimerState?,
    onStart: () -> Unit,
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
            if (task.completedToday) {
                // Already completed
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
            } else if (timerState == null) {
                // Timer not started
                TimerRing(
                    elapsed = 0,
                    target = durationConfig.targetSeconds,
                )
                Spacer(Modifier.height(24.dp))
                Text(
                    text = formatTime(durationConfig.targetSeconds),
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Light,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Duration required",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(32.dp))
                Button(onClick = onStart) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Start Timer")
                }
            } else {
                // Timer active (running or paused)
                val elapsed = timerState.elapsedSeconds
                val remaining = (timerState.targetSeconds - elapsed).coerceAtLeast(0)

                TimerRing(
                    elapsed = elapsed,
                    target = timerState.targetSeconds,
                )
                Spacer(Modifier.height(24.dp))
                Text(
                    text = formatTime(remaining),
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Light,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (timerState.running) "Running" else "Paused",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (timerState.running)
                        MaterialTheme.colorScheme.tertiary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "${formatTime(elapsed)} elapsed of ${formatTime(timerState.targetSeconds)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(32.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    // Cancel button
                    OutlinedButton(
                        onClick = { showCancelDialog = true },
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Icon(Icons.Default.Close, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Cancel")
                    }

                    // Pause / Resume button
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
        }
    }

    if (showCancelDialog) {
        AlertDialog(
            onDismissRequest = { showCancelDialog = false },
            title = { Text("Cancel timer?") },
            text = { Text("Your progress will be lost.") },
            confirmButton = {
                TextButton(onClick = {
                    showCancelDialog = false
                    onCancel()
                }) {
                    Text("Cancel Timer", color = MaterialTheme.colorScheme.error)
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
private fun TimerRing(
    elapsed: Int,
    target: Int,
) {
    val progress = if (target > 0) (elapsed.toFloat() / target).coerceIn(0f, 1f) else 0f
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(durationMillis = 500, easing = LinearEasing),
        label = "timer_progress",
    )
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val progressColor = MaterialTheme.colorScheme.primary

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(200.dp),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = 12.dp.toPx()
            val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
            val topLeft = Offset(strokeWidth / 2, strokeWidth / 2)

            // Background track
            drawArc(
                color = trackColor,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
            )

            // Progress arc
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
            imageVector = Icons.Default.Timer,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp),
        )
    }
}

private fun formatTime(totalSeconds: Int): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}
