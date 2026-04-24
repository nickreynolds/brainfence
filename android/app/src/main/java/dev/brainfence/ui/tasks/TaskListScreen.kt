package dev.brainfence.ui.tasks

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
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
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.brainfence.domain.model.BlockingRule
import dev.brainfence.domain.model.Task
import dev.brainfence.domain.recurrence.TimeGatePhase
import dev.brainfence.domain.recurrence.computeNextOccurrence
import dev.brainfence.domain.recurrence.computeTaskPhase
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskListScreen(
    activeTasks: List<Task>,
    completedTasks: List<Task>,
    upcomingTasks: List<Task>,
    selectedTab: HomeTab,
    activeRules: List<BlockingRule>,
    pendingTask: Task?,
    blockingStatus: BlockingStatusState,
    isAccessibilityEnabled: Boolean,
    hasLocationPermission: Boolean,
    needsUsageStatsPermission: Boolean,
    onLocationPermissionResult: (Boolean) -> Unit,
    onUsageStatsPermissionResult: () -> Unit,
    onSelectTab: (HomeTab) -> Unit,
    onTaskTap: (Task) -> Unit,
    onConfirmComplete: () -> Unit,
    onDismissComplete: () -> Unit,
    onSignOut: () -> Unit,
    onNavigateToDebug: () -> Unit = {},
    onNavigateToRules: () -> Unit = {},
    onCreateTask: () -> Unit = {},
) {
    val context = LocalContext.current

    // Foreground location permission launcher
    val fineLocationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (fineGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            onLocationPermissionResult(false)
        } else {
            onLocationPermissionResult(fineGranted)
        }
    }

    // Background location launcher (must be requested separately on Android 10+)
    val backgroundLocationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        onLocationPermissionResult(granted)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tasks") },
                actions = {
                    IconButton(onClick = onNavigateToRules) {
                        Icon(Icons.Default.Edit, contentDescription = "Blocking rules")
                    }
                    IconButton(onClick = onCreateTask) {
                        Icon(Icons.Default.Add, contentDescription = "Create task")
                    }
                    IconButton(onClick = onNavigateToDebug) {
                        Icon(Icons.Default.BugReport, contentDescription = "Debug logs")
                    }
                    IconButton(onClick = onSignOut) {
                        Icon(Icons.Default.Logout, contentDescription = "Sign out")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // Tab bar
            TabRow(selectedTabIndex = selectedTab.ordinal) {
                Tab(
                    selected = selectedTab == HomeTab.ACTIVE,
                    onClick = { onSelectTab(HomeTab.ACTIVE) },
                    text = {
                        Text(
                            if (activeTasks.isNotEmpty()) "Active (${activeTasks.size})"
                            else "Active"
                        )
                    },
                )
                Tab(
                    selected = selectedTab == HomeTab.COMPLETED,
                    onClick = { onSelectTab(HomeTab.COMPLETED) },
                    text = {
                        Text(
                            if (completedTasks.isNotEmpty()) "Done (${completedTasks.size})"
                            else "Done"
                        )
                    },
                )
                Tab(
                    selected = selectedTab == HomeTab.UPCOMING,
                    onClick = { onSelectTab(HomeTab.UPCOMING) },
                    text = {
                        Text(
                            if (upcomingTasks.isNotEmpty()) "Upcoming (${upcomingTasks.size})"
                            else "Upcoming"
                        )
                    },
                )
            }

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                // Banners only on the Active tab
                if (selectedTab == HomeTab.ACTIVE) {
                    if (!isAccessibilityEnabled) {
                        item(key = "a11y_banner") {
                            AccessibilityBanner()
                        }
                    }
                    if (!hasLocationPermission) {
                        item(key = "location_banner") {
                            val hasFineLocation = ContextCompat.checkSelfPermission(
                                context, Manifest.permission.ACCESS_FINE_LOCATION,
                            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                            LocationPermissionBanner(
                                onRequestPermission = {
                                    if (!hasFineLocation) {
                                        fineLocationLauncher.launch(
                                            arrayOf(
                                                Manifest.permission.ACCESS_FINE_LOCATION,
                                                Manifest.permission.ACCESS_COARSE_LOCATION,
                                            )
                                        )
                                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                        backgroundLocationLauncher.launch(
                                            Manifest.permission.ACCESS_BACKGROUND_LOCATION
                                        )
                                    }
                                },
                            )
                        }
                    }
                    if (needsUsageStatsPermission) {
                        item(key = "usage_stats_banner") {
                            UsageStatsBanner(
                                onResult = onUsageStatsPermissionResult,
                            )
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
                }

                val displayTasks = when (selectedTab) {
                    HomeTab.ACTIVE -> activeTasks
                    HomeTab.COMPLETED -> completedTasks
                    HomeTab.UPCOMING -> upcomingTasks
                }

                if (displayTasks.isEmpty()) {
                    item(key = "empty") {
                        val message = when (selectedTab) {
                            HomeTab.ACTIVE -> "All tasks completed or upcoming"
                            HomeTab.COMPLETED -> "No tasks completed yet today"
                            HomeTab.UPCOMING -> "No upcoming tasks"
                        }
                        Text(
                            text = message,
                            modifier = Modifier.padding(24.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                items(displayTasks, key = { it.id }) { task ->
                    TaskItem(
                        task = task,
                        showAsCompleted = selectedTab == HomeTab.COMPLETED,
                        showAsUpcoming = selectedTab == HomeTab.UPCOMING,
                        onClick = { onTaskTap(task) },
                    )
                }
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
    showAsCompleted: Boolean,
    showAsUpcoming: Boolean,
    onClick: () -> Unit,
) {
    val phase = computeTaskPhase(task.availableFrom, task.dueAt, Instant.now())

    val isCompletedRecurring = showAsUpcoming && task.completedToday && task.recurrenceType != null
    val isLocked = (showAsUpcoming && !isCompletedRecurring) || (phase == TimeGatePhase.BEFORE_START && !task.completedToday)
    val isOverdue = phase == TimeGatePhase.PAST_DUE && !task.completedToday

    ListItem(
        modifier = Modifier
            .clickable(enabled = !showAsCompleted && !isLocked && !isCompletedRecurring, onClick = onClick)
            .alpha(when {
                showAsCompleted || isCompletedRecurring -> 0.5f
                isLocked -> 0.4f
                else -> 1f
            }),
        headlineContent = { Text(task.title) },
        supportingContent = {
            when {
                isCompletedRecurring -> {
                    val zone = ZoneId.systemDefault()
                    val now = Instant.now()
                    val lastCompletion = task.lastCompletionAt?.let {
                        try { Instant.parse(it) } catch (_: Exception) { null }
                    }
                    val nextDue = computeNextOccurrence(
                        task.recurrenceType, task.recurrenceConfig,
                        lastCompletion, now, zone,
                    )
                    Text(
                        text = if (nextDue != null) "Next: ${formatNextDue(nextDue, zone)}" else "Completed",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                showAsUpcoming && task.availableFrom != null -> {
                    Text(
                        text = "Available at ${task.availableFrom}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                isOverdue && task.isBlockingCondition -> {
                    Text(
                        text = "Overdue \u2014 blocking apps until completed",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                isOverdue -> {
                    Text(
                        text = "Overdue",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                phase == TimeGatePhase.ACTIVE && task.dueAt != null && !task.completedToday -> {
                    Text(
                        text = "Complete before ${task.dueAt}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                showAsCompleted -> {
                    val recur = task.recurrenceType
                    if (recur != null) {
                        Text(
                            text = recur.replaceFirstChar { it.uppercase() },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                else -> {
                    val recur = task.recurrenceType
                    if (recur != null) {
                        val label = recur.replaceFirstChar { it.uppercase() }
                        val dueText = if (task.isBlockingCondition && task.dueAt != null) {
                            " \u00b7 Due by ${task.dueAt}"
                        } else if (task.isBlockingCondition) {
                            " \u00b7 Due today"
                        } else ""
                        Text(
                            text = "$label$dueText",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        leadingContent = {
            when {
                showAsCompleted || isCompletedRecurring -> Icon(
                    imageVector        = Icons.Default.CheckCircle,
                    contentDescription = "Completed today",
                    tint               = MaterialTheme.colorScheme.primary,
                )
                isLocked -> Icon(
                    imageVector        = Icons.Default.Lock,
                    contentDescription = "Not yet available",
                    tint               = MaterialTheme.colorScheme.outline,
                )
                isOverdue && task.isBlockingCondition -> Icon(
                    imageVector        = Icons.Default.Warning,
                    contentDescription = "Overdue",
                    tint               = MaterialTheme.colorScheme.error,
                )
                isOverdue -> Icon(
                    imageVector        = Icons.Default.Warning,
                    contentDescription = "Overdue",
                    tint               = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> Icon(
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
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = task.title,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                                val hint = blockingVerificationHint(task)
                                if (hint != null) {
                                    Text(
                                        text = hint,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f),
                                    )
                                }
                            }
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

/** Formats a future instant as a human-readable relative date, e.g. "Tomorrow", "Thursday", "Apr 30". */
private fun formatNextDue(nextDue: Instant, timeZone: ZoneId): String {
    val today = Instant.now().atZone(timeZone).toLocalDate()
    val nextDate = nextDue.atZone(timeZone).toLocalDate()
    val daysUntil = ChronoUnit.DAYS.between(today, nextDate)

    return when {
        daysUntil <= 0L -> "Later today"
        daysUntil == 1L -> "Tomorrow"
        daysUntil <= 6L -> nextDate.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
        else -> nextDate.format(DateTimeFormatter.ofPattern("MMM d"))
    }
}

/** Human-readable hint for non-trivial verification types shown in the blocking status card. */
private fun blockingVerificationHint(task: Task): String? = when {
    task.taskType == "routine" || task.taskType == "workout" -> "Requires routine completion"
    task.verificationType == "gps" -> "Requires GPS verification"
    task.verificationType == "meditation" -> "Requires meditation session"
    task.verificationType == "duration" -> "Requires timed session"
    else -> null
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
private fun LocationPermissionBanner(onRequestPermission: () -> Unit) {
    Card(
        onClick = onRequestPermission,
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
                imageVector = Icons.Default.LocationOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = "Location permission needed for GPS tasks. Tap to grant.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
private fun UsageStatsBanner(
    onResult: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current

    // Re-check permission when the user returns from settings
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                onResult()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Card(
        onClick = {
            context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = "Usage access needed to detect meditation app usage. Tap to grant.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
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
