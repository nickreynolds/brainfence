package dev.brainfence.ui.blocking

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import dev.brainfence.domain.model.Task
import dev.brainfence.ui.theme.BrainfenceTheme

/**
 * Full-screen, non-dismissable overlay shown when the user opens a blocked app.
 * Displays required tasks and allows completing manual tasks inline.
 * Dismisses itself only when all required tasks are completed.
 */
@AndroidEntryPoint
class BlockingActivity : ComponentActivity() {

    companion object {
        const val EXTRA_BLOCKED_PACKAGE = "blocked_package"
    }

    private val viewModel: BlockingViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Resolve app label from package name
        val packageName = intent.getStringExtra(EXTRA_BLOCKED_PACKAGE) ?: ""
        val appLabel = try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) {
            packageName
        }
        viewModel.setAppLabel(appLabel)

        setContent {
            BrainfenceTheme {
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()

                // Auto-dismiss when no longer blocked
                LaunchedEffect(uiState.isBlocked) {
                    if (!uiState.isBlocked) {
                        finish()
                    }
                }

                BlockingOverlayScreen(
                    state = uiState,
                    onCompleteTask = viewModel::completeTask,
                )
            }
        }
    }

    /** Prevent dismissal via back gesture/button while still blocked. */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Intentionally blocked — overlay cannot be dismissed by the user
    }
}

@Composable
private fun BlockingOverlayScreen(
    state: BlockingUiState,
    onCompleteTask: (String) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(48.dp))

            Icon(
                imageVector = Icons.Default.Block,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.error,
            )

            Spacer(Modifier.height(16.dp))

            Text(
                text = "${state.appLabel} is blocked",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = "Complete the following tasks to unblock:",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )

            Spacer(Modifier.height(24.dp))

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.requiredTasks, key = { it.id }) { task ->
                    RequiredTaskCard(
                        task = task,
                        onComplete = { onCompleteTask(task.id) },
                    )
                }
            }
        }
    }
}

private fun verificationHint(task: Task): String = when (task.verificationType) {
    "gps" -> "Requires GPS verification — open the app to start"
    "meditation" -> "Requires meditation session — open the app to start"
    "duration" -> "Requires timed session — open the app to start"
    "time_gate" -> "Only available during scheduled window"
    else -> "Tap Complete to finish"
}

@Composable
private fun RequiredTaskCard(
    task: Task,
    onComplete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (task.completedToday) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Completed",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp),
                )
            } else {
                Icon(
                    imageVector = Icons.Outlined.Circle,
                    contentDescription = "Not completed",
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(28.dp),
                )
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                )
                if (!task.completedToday) {
                    Text(
                        text = verificationHint(task),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (!task.completedToday && (task.verificationType == null || task.verificationType == "manual")) {
                Button(onClick = onComplete) {
                    Text("Complete")
                }
            }
        }
    }
}
