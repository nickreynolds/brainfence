package dev.brainfence.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * The Settings section: what used to live in the task list's ⋮ menu, grouped
 * like the desktop apps' Settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    email: String?,
    onOpenHomeLocation: () -> Unit,
    onOpenContemplations: () -> Unit,
    onOpenSetupGuide: () -> Unit,
    onOpenDebugLogs: () -> Unit,
    onSignOut: () -> Unit,
    onDeleteAccount: (onDone: (error: String?) -> Unit) -> Unit,
) {
    var showDeleteAccount by remember { mutableStateOf(false) }
    if (showDeleteAccount) {
        DeleteAccountDialog(onDelete = onDeleteAccount, onDismiss = { showDeleteAccount = false })
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Settings") }) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            item { SectionHeader("Blocking and reminders") }
            item {
                SettingsRow(
                    icon = Icons.Default.Home,
                    title = "Home location",
                    detail = "Where home-only blocking applies and arrival reminders fire",
                    onClick = onOpenHomeLocation,
                )
            }
            item {
                SettingsRow(
                    icon = Icons.Default.SelfImprovement,
                    title = "Contemplations",
                    detail = "Prompts sent through the day, and their decks",
                    onClick = onOpenContemplations,
                )
            }

            item { SectionHeader("Setup guide") }
            item {
                SettingsRow(
                    icon = Icons.Default.Explore,
                    title = "Run setup guide",
                    detail = "How Brainfence works, and the starter tasks and blocking rule",
                    onClick = onOpenSetupGuide,
                )
            }

            item { SectionHeader("Account") }
            item {
                ListItem(
                    leadingContent = { Icon(Icons.Default.AccountCircle, contentDescription = null) },
                    headlineContent = { Text(email ?: "Signed in") },
                    supportingContent = if (email != null) ({ Text("Signed in") }) else null,
                )
            }
            item {
                SettingsRow(
                    icon = Icons.Default.Logout,
                    title = "Sign out",
                    detail = "Sign out of your account on this phone",
                    onClick = onSignOut,
                )
            }
            item {
                ListItem(
                    modifier = Modifier.clickable { showDeleteAccount = true },
                    leadingContent = { Icon(Icons.Default.DeleteForever, contentDescription = null) },
                    headlineContent = { Text("Delete account…") },
                    colors = ListItemDefaults.colors(
                        headlineColor = MaterialTheme.colorScheme.error,
                        leadingIconColor = MaterialTheme.colorScheme.error,
                    ),
                )
            }

            item { SectionHeader("Troubleshooting") }
            item {
                SettingsRow(
                    icon = Icons.Default.BugReport,
                    title = "Debug logs",
                    detail = "Location, geofence and service events",
                    onClick = onOpenDebugLogs,
                )
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun SettingsRow(icon: ImageVector, title: String, detail: String, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { Icon(icon, contentDescription = null) },
        headlineContent = { Text(title) },
        supportingContent = { Text(detail) },
    )
}

/** shared/CONTRACTS.md, Account deletion. */
@Composable
private fun DeleteAccountDialog(
    onDelete: (onDone: (error: String?) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    var deleting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!deleting) onDismiss() },
        title = { Text("Delete your account?") },
        text = {
            Column {
                Text(
                    "This permanently deletes your account and everything in it, including your " +
                        "tasks, blocking rules and history, from Brainfence's servers and this " +
                        "phone. Blocking stops. You can't undo this.",
                )
                error?.let {
                    Spacer(Modifier.height(12.dp))
                    Text("Couldn't delete your account: $it", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !deleting,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                onClick = {
                    deleting = true
                    error = null
                    // On success the app signs out, which closes this screen.
                    onDelete { e ->
                        deleting = false
                        error = e
                    }
                },
            ) { Text(if (deleting) "Deleting…" else "Delete account") }
        },
        dismissButton = {
            TextButton(enabled = !deleting, onClick = onDismiss) { Text("Cancel") }
        },
    )
}
