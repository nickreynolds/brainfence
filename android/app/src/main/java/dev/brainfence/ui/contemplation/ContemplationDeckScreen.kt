package dev.brainfence.ui.contemplation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.brainfence.domain.model.Contemplation
import dev.brainfence.domain.model.ContemplationResponseType
import dev.brainfence.domain.model.DeckOrdering

/** Item being edited in the dialog; [id] null = new item. */
private data class ItemDraft(
    val id: String?,
    val title: String = "",
    val body: String = "",
    val attribution: String = "",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContemplationDeckScreen(
    form: DeckForm,
    items: List<Contemplation>,
    onUpdateForm: ((DeckForm) -> DeckForm) -> Unit,
    onSave: () -> Unit,
    onDeleteDeck: () -> Unit,
    onSaveItem: (id: String?, title: String, body: String, attribution: String) -> Unit,
    onDeleteItem: (String) -> Unit,
    onBack: () -> Unit,
) {
    var draft by remember { mutableStateOf<ItemDraft?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(form.name.ifBlank { "Deck" }) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete deck")
                    }
                },
            )
        },
    ) { padding ->
        if (!form.isLoaded) {
            Row(
                modifier = Modifier.fillMaxSize().padding(padding),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) { CircularProgressIndicator() }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = form.name,
                    onValueChange = { v -> onUpdateForm { it.copy(name = v) } },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                OutlinedTextField(
                    value = form.description,
                    onValueChange = { v -> onUpdateForm { it.copy(description = v) } },
                    label = { Text("Description (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Text("Order", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = form.ordering == DeckOrdering.SEQUENTIAL,
                        onClick = { onUpdateForm { it.copy(ordering = DeckOrdering.SEQUENTIAL) } },
                        label = { Text("In turn") },
                    )
                    FilterChip(
                        selected = form.ordering == DeckOrdering.RANDOM,
                        onClick = { onUpdateForm { it.copy(ordering = DeckOrdering.RANDOM) } },
                        label = { Text("Random") },
                    )
                }
            }
            item {
                Text("Response", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = form.responseType == ContemplationResponseType.NONE,
                        onClick = { onUpdateForm { it.copy(responseType = ContemplationResponseType.NONE) } },
                        label = { Text("Just read") },
                    )
                    FilterChip(
                        selected = form.responseType == ContemplationResponseType.AFFIRM,
                        onClick = { onUpdateForm { it.copy(responseType = ContemplationResponseType.AFFIRM) } },
                        label = { Text("Affirm button") },
                    )
                }
                if (form.responseType == ContemplationResponseType.AFFIRM) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = form.responseLabel,
                        onValueChange = { v -> onUpdateForm { it.copy(responseLabel = v) } },
                        label = { Text("Button label") },
                        placeholder = { Text("I remember") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            item {
                OutlinedTextField(
                    value = form.weight,
                    onValueChange = { v -> onUpdateForm { it.copy(weight = v.filter(Char::isDigit)) } },
                    label = { Text("Frequency") },
                    supportingText = { Text("Relative to other enabled decks, e.g. 4 vs 1 ≈ 80% of deliveries") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (form.error != null) {
                item {
                    Text(
                        text = form.error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            item {
                Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) { Text("Save deck") }
            }

            item {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text("Contemplations", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "Changes here save immediately.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(items, key = { it.id }) { item ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            draft = ItemDraft(
                                id = item.id,
                                title = item.title.orEmpty(),
                                body = item.body,
                                attribution = item.attribution.orEmpty(),
                            )
                        },
                ) {
                    Column(Modifier.padding(16.dp)) {
                        if (item.title != null) {
                            Text(item.title, style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(4.dp))
                        }
                        Text(item.body, style = MaterialTheme.typography.bodyMedium)
                        if (item.attribution != null) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "— ${item.attribution}",
                                style = MaterialTheme.typography.bodySmall,
                                fontStyle = FontStyle.Italic,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            item {
                TextButton(onClick = { draft = ItemDraft(id = null) }) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Add contemplation")
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    draft?.let { current ->
        ItemDialog(
            draft = current,
            onChange = { draft = it },
            onSave = {
                onSaveItem(current.id, current.title, current.body, current.attribution)
                draft = null
            },
            onDelete = current.id?.let { id -> { onDeleteItem(id); draft = null } },
            onDismiss = { draft = null },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete deck?") },
            text = { Text("Its contemplations are deleted too. Past deliveries stay in your history.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDeleteDeck() }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep") } },
        )
    }
}

@Composable
private fun ItemDialog(
    draft: ItemDraft,
    onChange: (ItemDraft) -> Unit,
    onSave: () -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (draft.id == null) "New contemplation" else "Edit contemplation") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = draft.title,
                    onValueChange = { onChange(draft.copy(title = it)) },
                    label = { Text("Title (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = draft.body,
                    onValueChange = { onChange(draft.copy(body = it)) },
                    label = { Text("Text") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = draft.attribution,
                    onValueChange = { onChange(draft.copy(attribution = it)) },
                    label = { Text("Attribution (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSave, enabled = draft.body.isNotBlank()) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
