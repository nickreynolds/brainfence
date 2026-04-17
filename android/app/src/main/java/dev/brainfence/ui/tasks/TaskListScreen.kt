package dev.brainfence.ui.tasks

import android.content.Intent
import android.graphics.Bitmap
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.brainfence.domain.model.Task

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskListScreen(
    tasks: List<Task>,
    pendingTask: Task?,
    blockingStatus: BlockingStatusState,
    isAccessibilityEnabled: Boolean,
    onTaskTap: (Task) -> Unit,
    onConfirmComplete: () -> Unit,
    onDismissComplete: () -> Unit,
    onSignOut: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tasks") },
                actions = {
                    IconButton(onClick = onSignOut) {
                        Icon(Icons.Default.Logout, contentDescription = "Sign out")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (!isAccessibilityEnabled) {
                item(key = "a11y_banner") {
                    AccessibilityBanner()
                }
            }
            if (blockingStatus.hasActiveRules) {
                item(key = "blocking_status") {
                    BlockingStatusCard(
                        blockingStatus = blockingStatus,
                        onQuickComplete = onTaskTap,
                    )
                }
            }
            items(tasks, key = { it.id }) { task ->
                TaskItem(
                    task     = task,
                    onClick  = { onTaskTap(task) },
                )
            }
        }
    }

    if (pendingTask != null) {
        AlertDialog(
            onDismissRequest = onDismissComplete,
            title   = { Text("Complete task?") },
            text    = { Text(pendingTask.title) },
            confirmButton = {
                TextButton(onClick = onConfirmComplete) { Text("Complete") }
            },
            dismissButton = {
                TextButton(onClick = onDismissComplete) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun TaskItem(
    task: Task,
    onClick: () -> Unit,
) {
    ListItem(
        modifier = Modifier
            .clickable(enabled = !task.completedToday, onClick = onClick)
            .alpha(if (task.completedToday) 0.5f else 1f),
        headlineContent = { Text(task.title) },
        supportingContent = {
            val verif = task.verificationType ?: "manual"
            val recur = task.recurrenceType ?: "one-off"
            Text("${task.taskType} · $verif · $recur")
        },
        leadingContent = {
            if (task.completedToday) {
                Icon(
                    imageVector        = Icons.Default.CheckCircle,
                    contentDescription = "Completed today",
                    tint               = MaterialTheme.colorScheme.primary,
                )
            } else {
                Icon(
                    imageVector        = Icons.Outlined.Circle,
                    contentDescription = "Not completed",
                    tint               = MaterialTheme.colorScheme.outline,
                )
            }
        },
        trailingContent = if (task.isBlockingCondition) {
            {
                Icon(
                    imageVector        = Icons.Default.Block,
                    contentDescription = "Blocking condition",
                    tint               = MaterialTheme.colorScheme.error,
                )
            }
        } else null,
        colors = ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BlockingStatusCard(
    blockingStatus: BlockingStatusState,
    onQuickComplete: (Task) -> Unit,
) {
    if (blockingStatus.blockedApps.isEmpty()) {
        // All clear
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ),
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "All clear \u2014 no apps blocked",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    } else {
        // Blocking active
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
            ),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Block,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    val count = blockingStatus.blockedApps.size
                    Text(
                        text = "$count app${if (count != 1) "s" else ""} blocked",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }

                Spacer(Modifier.height(8.dp))

                // Blocked app icons + labels
                FlowRow {
                    blockingStatus.blockedApps.forEach { app ->
                        Row(
                            modifier = Modifier.padding(end = 12.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AppIcon(
                                packageName = app.packageName,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = app.label,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }

                if (blockingStatus.incompleteTasks.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "Complete to unblock:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Spacer(Modifier.height(4.dp))

                    blockingStatus.incompleteTasks.forEach { task ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Circle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = task.title,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.weight(1f),
                            )
                            if (task.verificationType == null || task.verificationType == "manual") {
                                TextButton(onClick = { onQuickComplete(task) }) {
                                    Text("Complete", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppIcon(packageName: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmap = remember(packageName) {
        try {
            val drawable = context.packageManager.getApplicationIcon(packageName)
            val w = drawable.intrinsicWidth.coerceAtLeast(1)
            val h = drawable.intrinsicHeight.coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp)
            drawable.setBounds(0, 0, w, h)
            drawable.draw(canvas)
            bmp.asImageBitmap()
        } catch (_: Exception) {
            null
        }
    }
    if (bitmap != null) {
        Image(bitmap = bitmap, contentDescription = null, modifier = modifier)
    }
}

@Composable
private fun AccessibilityBanner() {
    val context = LocalContext.current
    Card(
        onClick = {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = "Accessibility service is disabled. Tap to enable app blocking.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}
