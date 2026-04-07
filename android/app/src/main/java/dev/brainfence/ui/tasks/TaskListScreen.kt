package dev.brainfence.ui.tasks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import dev.brainfence.domain.model.Task

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskListScreen(
    tasks: List<Task>,
    pendingTask: Task?,
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
