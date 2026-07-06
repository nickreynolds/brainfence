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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import dev.brainfence.domain.model.ShoppingItem
import dev.brainfence.domain.model.Task
import kotlinx.coroutines.launch

/**
 * A running shopping list: open items with checkboxes and a quick-add field.
 * Checking an item removes it from the list (with an undo snackbar); the
 * parent task itself is never completed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShoppingListScreen(
    task: Task,
    openItems: List<ShoppingItem>,
    onAddItem: (String) -> Unit,
    onCheckOff: (ShoppingItem, onDone: () -> Unit) -> Unit,
    onUndoCheckOff: (String) -> Unit,
    onDeleteItem: (String) -> Unit,
    onEditTask: () -> Unit,
    onBack: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(task.title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onEditTask) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit list settings")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            var newItemText by remember { mutableStateOf("") }
            val submitItem = {
                if (newItemText.isNotBlank()) {
                    onAddItem(newItemText)
                    newItemText = ""
                }
            }
            OutlinedTextField(
                value = newItemText,
                onValueChange = { newItemText = it },
                label = { Text("Add item") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submitItem() }),
                trailingIcon = {
                    IconButton(onClick = submitItem, enabled = newItemText.isNotBlank()) {
                        Icon(Icons.Default.Add, contentDescription = "Add item")
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )

            if (openItems.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ShoppingCart,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(48.dp),
                    )
                    Text(
                        text = "Nothing to buy",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "Items you add here trigger a reminder when you arrive at the configured location.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(openItems, key = { it.id }) { item ->
                        ListItem(
                            headlineContent = { Text(item.title) },
                            leadingContent = {
                                Checkbox(
                                    checked = false,
                                    onCheckedChange = {
                                        onCheckOff(item) {
                                            scope.launch {
                                                val result = snackbarHostState.showSnackbar(
                                                    message = "Bought ${item.title}",
                                                    actionLabel = "Undo",
                                                    duration = androidx.compose.material3.SnackbarDuration.Short,
                                                )
                                                if (result == SnackbarResult.ActionPerformed) {
                                                    onUndoCheckOff(item.id)
                                                }
                                            }
                                        }
                                    },
                                )
                            },
                            trailingContent = {
                                IconButton(onClick = { onDeleteItem(item.id) }) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "Delete item",
                                        modifier = Modifier.size(20.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                            colors = ListItemDefaults.colors(
                                containerColor = MaterialTheme.colorScheme.surface,
                            ),
                        )
                    }
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.ShoppingCart,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "${openItems.size} item${if (openItems.size != 1) "s" else ""} to buy",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(32.dp))
                    }
                }
            }
        }
    }
}
