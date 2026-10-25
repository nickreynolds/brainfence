package dev.brainfence.ui.onboarding

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.brainfence.data.apps.InstalledApp
import dev.brainfence.domain.blocking.RuleContracts
import dev.brainfence.domain.onboarding.Onboarding
import dev.brainfence.domain.onboarding.OnboardingPlan
import dev.brainfence.domain.onboarding.StarterKey
import dev.brainfence.domain.recurrence.parseTaskTime
import dev.brainfence.ui.components.LocationPickerMap
import java.util.Locale

private fun starterIcon(key: StarterKey): ImageVector = when (key) {
    StarterKey.MORNING_ROUTINE -> Icons.Default.Checklist
    StarterKey.MORNING_WALK -> Icons.AutoMirrored.Filled.DirectionsWalk
    StarterKey.MEDITATION -> Icons.Default.SelfImprovement
    StarterKey.JOURNALING -> Icons.AutoMirrored.Filled.Article
    StarterKey.GROCERIES -> Icons.Default.ShoppingCart
    StarterKey.FIVE_REMEMBRANCES -> Icons.Default.FormatQuote
}

/**
 * The first-time setup guide: what Brainfence does, then a starter set of tasks
 * and a blocking rule (shared/CONTRACTS.md, Onboarding).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GuideScreen(
    state: GuideState,
    plan: OnboardingPlan,
    installedApps: List<InstalledApp>,
    onNext: () -> Unit,
    onBack: () -> Unit,
    onSkip: () -> Unit,
    onSetStarter: (StarterKey, Boolean) -> Unit,
    onSetWindow: (StarterKey, String?, String?) -> Unit,
    onSetHome: (PlaceInput) -> Unit,
    onSetStore: (PlaceInput) -> Unit,
    onToggleCompanionApp: (String) -> Unit,
    onToggleBlockedApp: (String) -> Unit,
    onToggleDomain: (String) -> Unit,
    onAddDomain: (String) -> Boolean,
    onCreate: () -> Unit,
    onFinish: () -> Unit,
) {
    val steps = state.steps
    val index = steps.indexOf(state.step)
    val progress = if (state.step == GuideStep.WELCOME || state.step == GuideStep.DONE) null
        else index.toFloat() / (steps.size - 2)

    when (state.step) {
        GuideStep.WELCOME -> OnboardingPage(
            title = "Welcome to Brainfence",
            progress = null,
            footer = {
                TextButton(onClick = onSkip) { Text("Skip setup") }
                Button(onClick = onNext) { Text("Get started") }
            },
        ) {
            Muted("Brainfence keeps distracting apps and websites locked until you've done the things that matter to you today.")
            Point(Icons.Default.Checklist, "Tasks with a time window",
                "Each task has a window: it can be done from its start time, and it's due by its end time. Repeating tasks come back every day.")
            Point(Icons.Default.Lock, "Blocking rules",
                "A rule lists apps and sites to block, and the tasks that unlock them. Once a task is past due and not done, the rule's apps and sites stay blocked until you finish it.")
            Point(Icons.Default.Sync, "On all your devices",
                "Tasks and rules sync to your phone and computers. Each device blocks its own apps.")
            Muted("The next few steps set up a small starter routine. You can change any of it later.")
        }

        GuideStep.STARTERS -> {
            val valid = state.starters.isNotEmpty() && state.starters.values.all { c ->
                listOfNotNull(c.availableFrom, c.dueAt).all { parseTaskTime(it) != null }
            }
            OnboardingPage(
                title = "Pick your starter tasks",
                progress = progress,
                footer = { NavButtons(onBack, onNext, nextEnabled = valid) },
            ) {
                Muted("Turn on the ones you want. Tasks marked \"Unlocks apps\" will be part of your blocking rule.")
                for (def in Onboarding.STARTERS) {
                    val choice = state.starters[def.key]
                    StarterCard(
                        icon = starterIcon(def.key),
                        title = def.title,
                        summary = def.summary,
                        blocks = def.blocks,
                        enabled = choice != null,
                        onEnabled = { onSetStarter(def.key, it) },
                    ) {
                        if (choice != null && def.hasWindow) {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                TimeField("Available from", choice.availableFrom ?: def.availableFrom!!, Modifier.weight(1f)) {
                                    onSetWindow(def.key, it, null)
                                }
                                TimeField("Due by", choice.dueAt ?: def.dueAt!!, Modifier.weight(1f)) {
                                    onSetWindow(def.key, null, it)
                                }
                            }
                        }
                    }
                }
            }
        }

        GuideStep.PLACES -> OnboardingPage(
            title = "Where are home and the store?",
            progress = progress,
            footer = { NavButtons(onBack, onNext) },
        ) {
            Muted("Location tasks complete on their own. Tap the map to drop a pin, or use the button for where you are now.")
            LocationPermissionPrompt()
            if (StarterKey.MORNING_WALK in state.starters) {
                PlacePicker("Home", "Morning Walk completes when you leave here. It's also saved as this phone's home location.", state.home, state.homeRadiusMeters, onSetHome)
            }
            if (StarterKey.GROCERIES in state.starters) {
                PlacePicker("Grocery store", "Brainfence reminds you of your list when you're near here.", state.store, Onboarding.GROCERIES_RADIUS_M, onSetStore)
            }
            if (plan.deferredSteps.isNotEmpty()) {
                Muted("Without a place, ${plan.deferredSteps.joinToString(" and ") { Onboarding.starter(it.key).title }} will be asked about next time Brainfence opens.")
            }
        }

        GuideStep.MEDITATION -> OnboardingPage(
            title = "Meditation apps",
            progress = progress,
            footer = { NavButtons(onBack, onNext) },
        ) {
            Muted("Meditation can use the built-in timer. If you use an app like Calm or Insight Timer instead, pick it: time spent in it counts toward your ten minutes.")
            AppChecklist(installedApps, state.companionApps, onToggleCompanionApp)
        }

        GuideStep.BLOCKING -> OnboardingPage(
            title = "What should be blocked?",
            progress = progress,
            footer = { NavButtons(onBack, onNext) },
        ) {
            val conditions = Onboarding.STARTERS.filter { it.blocks && it.key in state.starters }
            Muted(
                if (conditions.isEmpty()) {
                    "None of your starters unlock apps, so no rule will be made. Go back and turn on one marked \"Unlocks apps\" to block something."
                } else {
                    "Until ${conditions.joinToString(", ") { it.title }} ${if (conditions.size > 1) "are" else "is"} done, these will be blocked once each task is past due."
                },
            )
            SectionLabel("Apps on this phone")
            AppChecklist(installedApps, state.blockedApps, onToggleBlockedApp)
            SectionLabel("Websites")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (Onboarding.SUGGESTED_DOMAINS + state.domains).distinct().forEach { d ->
                    FilterChip(selected = d in state.domains, onClick = { onToggleDomain(d) }, label = { Text(d) })
                }
            }
            DomainInput(onAddDomain)
            Point(Icons.Default.Schedule, "Loosening takes a day",
                "Adding to a rule applies at once. Removing an app, a site or a task waits 24 hours, so blocking can't be undone on a whim.")
        }

        GuideStep.REVIEW -> OnboardingPage(
            title = "Ready to go",
            progress = progress,
            footer = {
                TextButton(onClick = onBack) { Text("Back") }
                Button(onClick = onCreate, enabled = !state.isSaving) { Text(if (state.isSaving) "Creating…" else "Create") }
            },
        ) {
            for (t in plan.tasks) {
                ReviewRow(starterIcon(t.key), t.title, t.availableFrom?.let { "Daily, $it–${t.dueAt}" } ?: "Shopping list")
            }
            if (plan.addRemembrances) ReviewRow(starterIcon(StarterKey.FIVE_REMEMBRANCES), "The Five Remembrances", "Delivered on this phone")
            for (d in plan.deferredSteps) {
                ReviewRow(starterIcon(d.key), Onboarding.starter(d.key).title, "Needs a place: asked again next time")
            }
            val rule = plan.rule
            if (rule != null) {
                val parts = listOfNotNull(
                    rule.blockedApps.size.takeIf { it > 0 }?.let { if (it == 1) "1 app" else "$it apps" },
                    rule.blockedDomains.takeIf { it.isNotEmpty() }?.joinToString(", "),
                )
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    ReviewRow(Icons.Default.Lock, "Rule \"${rule.name}\"", parts.joinToString(" · "))
                }
            } else {
                Muted("No blocking rule: pick something to block and a task that unlocks it. You can add rules later under Blocking rules.")
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }

        GuideStep.DONE -> OnboardingPage(
            title = "You're set up",
            progress = null,
            footer = {
                Spacer(Modifier.width(1.dp))
                Button(onClick = onFinish) { Text("Go to my tasks") }
            },
        ) {
            Point(Icons.Default.PlayArrow, "Doing a task",
                "Tap a task on the Tasks screen to start it. Routines walk you through each step, meditation runs a timer, and Morning Walk completes when you leave home.")
            Point(Icons.Default.Block, "When something is blocked",
                "Brainfence covers the app and lists what's left to do. Finish the task and it unlocks straight away.")
            if (plan.addRemembrances) {
                Point(Icons.Default.FormatQuote, "Contemplations",
                    "A remembrance arrives as a notification a few times a day. Tap \"I remember\". Change how often under Contemplations.")
            }
            Point(Icons.Default.Settings, "Run this again", "⋮ menu → Setup guide.")
        }
    }
}

/** A device joining an account that finished the guide elsewhere: the per-device parts. */
@Composable
fun DeviceSetupScreen(
    state: DeviceSetupState,
    deferredPlan: OnboardingPlan,
    installedApps: List<InstalledApp>,
    onToggleBlockedApp: (String) -> Unit,
    onToggleCompanionApp: (String) -> Unit,
    onSetKeep: (StarterKey, Boolean) -> Unit,
    onSetHome: (PlaceInput) -> Unit,
    onSetStore: (PlaceInput) -> Unit,
    onSetDeliverContemplations: (Boolean) -> Unit,
    onSkip: () -> Unit,
    onDone: () -> Unit,
) {
    OnboardingPage(
        title = "Set up this phone",
        progress = null,
        footer = {
            TextButton(onClick = onSkip, enabled = !state.isSaving) { Text("Skip") }
            Button(onClick = onDone, enabled = !state.isSaving && !state.isLoading) { Text(if (state.isSaving) "Saving…" else "Done") }
        },
    ) {
        Muted("Your tasks and rules are already here from your other devices. A few things are specific to this phone.")
        val labels = installedApps.associate { it.packageName to it.label }
        state.ruleName?.let { name ->
            SectionLabel("Block on this phone with \"$name\"")
            if (state.alreadyBlocked.isNotEmpty()) {
                Muted("Already blocked here: ${state.alreadyBlocked.joinToString(", ") { labels[it] ?: it }}")
            }
            AppChecklist(installedApps.filter { it.packageName !in state.alreadyBlocked }, state.blockedApps, onToggleBlockedApp)
        }
        state.companionApps?.let { selected ->
            SectionLabel("Meditation apps on this phone")
            Muted("Time spent in these counts toward your Meditation task.")
            AppChecklist(installedApps, selected, onToggleCompanionApp)
        }
        if (state.deferred.isNotEmpty()) LocationPermissionPrompt()
        for (d in state.deferred) {
            val def = Onboarding.starter(d.key)
            StarterCard(
                icon = starterIcon(d.key),
                title = "Add ${def.title}",
                summary = "Chosen on another device that couldn't set its location.",
                blocks = def.blocks && state.ruleName != null,
                enabled = d.key in state.keep,
                onEnabled = { onSetKeep(d.key, it) },
            ) {}
        }
        if (state.savedHome == null || StarterKey.MORNING_WALK in state.keep) {
            PlacePicker(
                "Home",
                if (StarterKey.MORNING_WALK in state.keep) "Morning Walk completes when you leave here. Also used for home-only blocking."
                else "Used for tasks that only block at home.",
                state.home, state.savedHome?.radiusMeters ?: 100, onSetHome,
            )
        }
        if (StarterKey.GROCERIES in state.keep) {
            PlacePicker("Grocery store", "Brainfence reminds you of your list when you're near here.", state.store, Onboarding.GROCERIES_RADIUS_M, onSetStore)
        }
        if (deferredPlan.deferredSteps.isNotEmpty()) {
            Muted("Without a place, ${deferredPlan.deferredSteps.joinToString(" and ") { Onboarding.starter(it.key).title }} will be asked about again next time.")
        }
        if (state.askContemplations) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Contemplations on this phone", style = MaterialTheme.typography.titleSmall)
                    Muted("Deliver your contemplation decks here as notifications.")
                }
                Switch(checked = state.deliverContemplations, onCheckedChange = onSetDeliverContemplations)
            }
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

// ── Building blocks ────────────────────────────────────────────────────────

@Composable
private fun OnboardingPage(
    title: String,
    progress: Float?,
    footer: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            if (progress != null) {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
            }
            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(title, style = MaterialTheme.typography.headlineMedium)
                content()
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) { footer() }
        }
    }
}

@Composable
private fun NavButtons(onBack: () -> Unit, onNext: () -> Unit, nextEnabled: Boolean = true) {
    TextButton(onClick = onBack) { Text("Back") }
    Button(onClick = onNext, enabled = nextEnabled) { Text("Next") }
}

@Composable
private fun Muted(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun Point(icon: ImageVector, title: String, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 2.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Muted(text)
        }
    }
}

@Composable
private fun ReviewRow(icon: ImageVector, title: String, detail: String) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(detail) },
        leadingContent = { Icon(icon, contentDescription = null) },
    )
}

@Composable
private fun StarterCard(
    icon: ImageVector,
    title: String,
    summary: String,
    blocks: Boolean,
    enabled: Boolean,
    onEnabled: (Boolean) -> Unit,
    extra: @Composable ColumnScope.() -> Unit,
) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 2.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    if (blocks) Text("Unlocks apps", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    Muted(summary)
                }
                Switch(checked = enabled, onCheckedChange = onEnabled)
            }
            extra()
        }
    }
}

@Composable
private fun TimeField(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text("$label (HH:mm)") },
        singleLine = true,
        isError = parseTaskTime(value) == null,
        modifier = modifier,
    )
}

/** A searchable checklist of installed apps, in a fixed-height box so the page can scroll. */
@Composable
private fun AppChecklist(apps: List<InstalledApp>, selected: Set<String>, onToggle: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val chosen = apps.filter { it.packageName in selected }
    if (chosen.isNotEmpty()) Muted("Selected: ${chosen.joinToString(", ") { it.label }}")
    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        label = { Text("Search apps") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedCard(Modifier.fillMaxWidth().height(300.dp)) {
        if (apps.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Muted("Loading apps…") }
        } else {
            val filtered = apps.filter {
                query.isBlank() || it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true)
            }
            LazyColumn {
                items(filtered, key = { it.packageName }) { app ->
                    ListItem(
                        modifier = Modifier.clickable { onToggle(app.packageName) },
                        headlineContent = { Text(app.label) },
                        leadingContent = { app.icon?.let { Image(bitmap = it, contentDescription = null, modifier = Modifier.size(32.dp)) } },
                        trailingContent = { Checkbox(checked = app.packageName in selected, onCheckedChange = { onToggle(app.packageName) }) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DomainInput(onAdd: (String) -> Boolean) {
    var text by rememberSaveable { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("Add a site, e.g. news.ycombinator.com") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(
            onClick = { if (onAdd(text)) text = "" },
            enabled = RuleContracts.normalizeDomain(text) != null,
        ) { Text("Add") }
    }
}

@Composable
private fun PlacePicker(title: String, help: String, place: PlaceInput, radiusMeters: Int, onChange: (PlaceInput) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Muted(help)
            LocationPickerMap(
                latitude = place.latitude.toDoubleOrNull(),
                longitude = place.longitude.toDoubleOrNull(),
                radiusMeters = radiusMeters,
                onLocationSelected = { lat, lng ->
                    onChange(PlaceInput(String.format(Locale.US, "%.6f", lat), String.format(Locale.US, "%.6f", lng)))
                },
                modifier = Modifier.fillMaxWidth().height(240.dp),
            )
            Muted(place.toChoice()?.let { "Pinned at ${place.latitude}, ${place.longitude}" } ?: "No place yet")
        }
    }
}

/** Location tasks need location access; ask here rather than on the task list. */
@Composable
private fun LocationPermissionPrompt() {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
    }
    if (!granted) {
        OutlinedButton(onClick = {
            launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }) { Text("Allow location access") }
    }
}
