package dev.brainfence.ui.review

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.brainfence.data.report.ReportLibrary
import dev.brainfence.domain.model.JournalEntry
import dev.brainfence.domain.model.Metric
import dev.brainfence.domain.model.Report
import dev.brainfence.ui.journal.JournalHistoryList
import dev.brainfence.ui.report.ReportsList

/** Review's tabs. Journal comes first; more kinds of entries will join it. */
enum class ReviewTab(val label: String) { JOURNAL("Journal"), REPORTS("Reports") }

/** The Review section: looking back over journal entries and reports. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(
    journalEntries: List<JournalEntry>,
    reports: List<Report>,
    metrics: List<Metric>,
    starters: List<ReportLibrary.StarterReport>,
    onOpenReport: (String) -> Unit,
    onOpenMetric: (String) -> Unit,
    onAddStarter: (String) -> Unit,
    onCreateReport: () -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(ReviewTab.JOURNAL) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Review") }) },
        floatingActionButton = {
            if (tab == ReviewTab.REPORTS) {
                ExtendedFloatingActionButton(
                    text = { Text("New report") },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    onClick = onCreateReport,
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            TabRow(selectedTabIndex = tab.ordinal) {
                ReviewTab.entries.forEach { t ->
                    Tab(selected = tab == t, onClick = { tab = t }, text = { Text(t.label) })
                }
            }
            when (tab) {
                ReviewTab.JOURNAL -> JournalHistoryList(entries = journalEntries)
                ReviewTab.REPORTS -> ReportsList(
                    reports = reports,
                    metrics = metrics,
                    starters = starters,
                    onOpenReport = onOpenReport,
                    onOpenMetric = onOpenMetric,
                    onAddStarter = onAddStarter,
                )
            }
        }
    }
}
