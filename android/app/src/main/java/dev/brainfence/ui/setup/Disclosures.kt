package dev.brainfence.ui.setup

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/*
 * Google Play's "prominent disclosure" for the accessibility service and
 * background location: shown before the user is sent to grant either, and only
 * an explicit "Agree" continues. Keep the wording in step with what the app
 * does, and with the declarations in store/play/permissions.md.
 */

const val ACCESSIBILITY_DISCLOSURE_TITLE = "Brainfence uses the Accessibility Service"

const val ACCESSIBILITY_DISCLOSURE =
    "Brainfence uses Android's Accessibility Service API to block the apps and websites in " +
        "your blocking rules until you finish your tasks:\n\n" +
        "• It sees which app is open, so it can cover a blocked app with the blocking screen.\n" +
        "• In supported browsers, it reads the web address in the address bar, so it can " +
        "block the websites in your rules. While websites are blocked, it also covers " +
        "browsers it can't read.\n\n" +
        "Brainfence doesn't read anything else on your screen, and it doesn't type, tap or " +
        "change anything for you. The apps and addresses it sees stay on this phone: they " +
        "aren't saved, or sent to Brainfence or anyone else."

const val BACKGROUND_LOCATION_DISCLOSURE_TITLE = "Location in the background"

const val BACKGROUND_LOCATION_DISCLOSURE =
    "Brainfence collects location data to complete GPS tasks when you arrive at or leave a " +
        "place, to remind you about shopping lists near the store, and to tell whether you're " +
        "home for rules that only block at home, even when the app is closed or not in use.\n\n" +
        "When a GPS task is completed, the location that completed it is saved with the " +
        "completion in your account, as proof. So are the places you choose for your tasks. " +
        "Otherwise your location stays on this phone, and it's never shared with anyone else."

/** The disclosure as a dialog; [onAgree] goes on to the system prompt or settings. */
@Composable
fun DisclosureDialog(
    title: String,
    text: String,
    onAgree: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text, modifier = Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = onAgree) { Text("Agree") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } },
    )
}
