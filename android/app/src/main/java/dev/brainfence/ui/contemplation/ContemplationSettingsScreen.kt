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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.brainfence.data.contemplation.ContemplationLibrary
import dev.brainfence.domain.model.ContemplationDeck
import dev.brainfence.domain.model.ContemplationDelivery
import dev.brainfence.domain.model.ContemplationResponses
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContemplationSettingsScreen(
    form: ContemplationScheduleForm,
    decks: List<ContemplationDeck>,
    availableLibraryDecks: List<ContemplationLibrary.LibraryDeck>,
    recentDeliveries: List<ContemplationDelivery>,
    onSetEnabled: (Boolean) -> Unit,
    onSetPerDay: (String) -> Unit,
    onSetWindowStart: (String) -> Unit,
    onSetWindowEnd: (String) -> Unit,
    onSaveSchedule: () -> Unit,
    onClearError: () -> Unit,
    onSendNow: () -> Unit,
    onAddFromLibrary: (String) -> Unit,
    onSetDeckEnabled: (String, Boolean) -> Unit,
    onOpenDeck: (String) -> Unit,
    onCreateDeck: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Contemplations") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "\"To be a happy person, one must contemplate death five times daily.\" " +
                        "Short contemplations arrive at random times within your waking hours.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ── Schedule ─────────────────────────────────────────────
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Deliver contemplations",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = form.enabled, onCheckedChange = onSetEnabled)
                }
            }
            if (form.error != null) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = form.error,
                                modifier = Modifier.weight(1f),
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            TextButton(onClick = onClearError) { Text("Dismiss") }
                        }
                    }
                }
            }
            if (form.message != null) {
                item {
                    Text(
                        text = form.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = form.perDay,
                        onValueChange = onSetPerDay,
                        label = { Text("Per day") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = form.windowStart,
                        onValueChange = onSetWindowStart,
                        label = { Text("From") },
                        placeholder = { Text("08:00") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = form.windowEnd,
                        onValueChange = onSetWindowEnd,
                        label = { Text("Until") },
                        placeholder = { Text("21:00") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onSaveSchedule, modifier = Modifier.weight(1f)) {
                        Text("Save schedule")
                    }
                    OutlinedButton(onClick = onSendNow, modifier = Modifier.weight(1f)) {
                        Text("Send one now")
                    }
                }
            }

            // ── Decks ────────────────────────────────────────────────
            item {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text("Decks", style = MaterialTheme.typography.titleMedium)
                if (decks.size > 1) {
                    Text(
                        text = "When several decks are on, each delivery picks one in proportion to its frequency.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(decks, key = { it.id }) { deck ->
                DeckRow(
                    deck = deck,
                    onToggle = { onSetDeckEnabled(deck.id, it) },
                    onClick = { onOpenDeck(deck.id) },
                )
            }
            items(availableLibraryDecks, key = { "library-${it.key}" }) { libraryDeck ->
                LibraryDeckRow(libraryDeck, onAdd = { onAddFromLibrary(libraryDeck.key) })
            }
            item {
                TextButton(onClick = onCreateDeck) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("New deck")
                }
            }

            // ── History ──────────────────────────────────────────────
            if (recentDeliveries.isNotEmpty()) {
                item {
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    val today = LocalDate.now()
                    val todays = recentDeliveries.filter { localDateOf(it.deliveredAt) == today }
                    val affirmed = todays.count { it.response == ContemplationResponses.AFFIRMED }
                    Text("Recent", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "Today: ${todays.size} delivered · $affirmed affirmed",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(recentDeliveries, key = { "delivery-${it.id}" }) { delivery ->
                    DeliveryRow(delivery)
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun DeckRow(deck: ContemplationDeck, onToggle: (Boolean) -> Unit, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(deck.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "${deck.itemCount} item${if (deck.itemCount == 1) "" else "s"} · " +
                        "frequency ${deck.weight}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = deck.isEnabled, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun LibraryDeckRow(libraryDeck: ContemplationLibrary.LibraryDeck, onAdd: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(libraryDeck.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = libraryDeck.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onAdd) { Text("Add") }
        }
    }
}

@Composable
private fun DeliveryRow(delivery: ContemplationDelivery) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = delivery.title ?: delivery.body,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = formatDeliveredAt(delivery.deliveredAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (delivery.response == ContemplationResponses.AFFIRMED) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = "Affirmed",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

private val DELIVERED_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm")

private fun localDateOf(iso: String): LocalDate? =
    runCatching { Instant.parse(iso).atZone(ZoneId.systemDefault()).toLocalDate() }.getOrNull()

private fun formatDeliveredAt(iso: String): String =
    runCatching { Instant.parse(iso).atZone(ZoneId.systemDefault()).format(DELIVERED_FORMAT) }
        .getOrDefault(iso)
