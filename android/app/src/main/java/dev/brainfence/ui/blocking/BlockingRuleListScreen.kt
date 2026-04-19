package dev.brainfence.ui.blocking

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockingRuleListScreen(
    rules: List<RuleListItem>,
    onRuleTap: (String) -> Unit,
    onCreateRule: () -> Unit,
    onDeleteRule: (String) -> Unit,
    onBack: () -> Unit,
) {
    var deleteTarget by remember { mutableStateOf<RuleListItem?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Blocking Rules") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onCreateRule) {
                Icon(Icons.Default.Add, contentDescription = "Create rule")
            }
        },
    ) { padding ->
        if (rules.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(32.dp),
            ) {
                Text(
                    text = "No blocking rules yet",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "Tap + to create a rule that blocks apps until you complete your tasks.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                items(rules, key = { it.rule.id }) { item ->
                    ListItem(
                        modifier = Modifier
                            .clickable { onRuleTap(item.rule.id) }
                            .alpha(if (item.rule.isActive) 1f else 0.5f),
                        headlineContent = { Text(item.rule.name.ifBlank { "Untitled rule" }) },
                        supportingContent = {
                            val parts = mutableListOf<String>()
                            if (item.blockedAppLabels.isNotEmpty()) {
                                val count = item.blockedAppLabels.size
                                parts.add("$count app${if (count != 1) "s" else ""}")
                            }
                            if (item.rule.blockedDomains.isNotEmpty()) {
                                val count = item.rule.blockedDomains.size
                                parts.add("$count domain${if (count != 1) "s" else ""}")
                            }
                            if (!item.rule.isActive) parts.add("disabled")
                            Text(parts.joinToString(" \u00b7 ").ifEmpty { "No apps or domains" })
                        },
                        trailingContent = {
                            if (item.hasPendingChanges) {
                                Icon(
                                    imageVector = Icons.Default.Schedule,
                                    contentDescription = "Pending changes",
                                    tint = MaterialTheme.colorScheme.tertiary,
                                )
                            }
                        },
                    )
                }
            }
        }
    }

    deleteTarget?.let { item ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete rule?") },
            text = { Text("\"${item.rule.name}\" will be permanently deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteRule(item.rule.id)
                    deleteTarget = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            },
        )
    }
}
