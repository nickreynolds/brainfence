package dev.brainfence.ui.feedback

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.brainfence.domain.feedback.Feedback

/**
 * The short feedback form, from Settings and the setup guide's last page.
 * shared/CONTRACTS.md (Feedback).
 */
@Composable
fun FeedbackDialog(
    onSend: (message: String, onDone: (error: String?) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    var message by rememberSaveable { mutableStateOf("") }
    var sending by rememberSaveable { mutableStateOf(false) }
    var sent by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }

    if (sent) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Thanks!") },
            text = { Text("Your feedback is on its way. If you're offline, it's sent when you're back online.") },
            confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        )
        return
    }

    val length = message.trim().let { it.codePointCount(0, it.length) }
    AlertDialog(
        onDismissRequest = { if (!sending) onDismiss() },
        title = { Text("Send feedback") },
        text = {
            Column {
                Text("Something confusing, broken or missing? Or something you like? It comes straight to us.")
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = message,
                    onValueChange = { message = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("What's on your mind?") },
                    minLines = 4,
                    maxLines = 8,
                    enabled = !sending,
                    isError = length > Feedback.MAX_LENGTH,
                    supportingText = if (length > Feedback.MAX_LENGTH - 500) {
                        { Text("$length / ${Feedback.MAX_LENGTH}") }
                    } else {
                        null
                    },
                )
                Text(
                    "Saved with your account, along with the platform and app version.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text("Couldn't send it: $it", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !sending && Feedback.normalize(message) != null,
                onClick = {
                    sending = true
                    error = null
                    onSend(message) { e ->
                        sending = false
                        error = e
                        sent = e == null
                    }
                },
            ) { Text(if (sending) "Sending…" else "Send") }
        },
        dismissButton = {
            TextButton(enabled = !sending, onClick = onDismiss) { Text("Cancel") }
        },
    )
}
