package dev.brainfence.ui.report

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.brainfence.data.report.OptionDraft
import dev.brainfence.data.report.ReportLibrary
import dev.brainfence.data.report.ReportRepository
import dev.brainfence.domain.model.Metric
import dev.brainfence.domain.model.MetricKind
import dev.brainfence.domain.model.Report
import dev.brainfence.domain.report.validateMetricDraft
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Default options for a new metric — the common none/moderate/a lot scale. */
private val NEW_METRIC_OPTIONS = listOf("None", "Moderate", "A lot")

// ── Reports list ─────────────────────────────────────────────────────

@HiltViewModel
class ReportsViewModel @Inject constructor(
    private val repository: ReportRepository,
) : ViewModel() {

    val reports: StateFlow<List<Report>> = repository.watchReports()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val metrics: StateFlow<List<Metric>> = repository.watchMetrics()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Starter reports not added yet. */
    val availableStarters: StateFlow<List<ReportLibrary.StarterReport>> = repository.watchReports()
        .map { reports ->
            val added = reports.mapNotNull { it.libraryKey }.toSet()
            ReportLibrary.reports.filter { it.key !in added }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun addStarter(key: String) {
        val starter = ReportLibrary.reports.firstOrNull { it.key == key } ?: return
        viewModelScope.launch { repository.addFromLibrary(starter) }
    }

    fun createReport(onCreated: (String) -> Unit) {
        viewModelScope.launch { onCreated(repository.createReport("New report")) }
    }
}

// ── Report editor ────────────────────────────────────────────────────

data class ReportEditorForm(
    val name: String = "",
    val includeNote: Boolean = true,
    val isLoaded: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class ReportEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: ReportRepository,
) : ViewModel() {

    private val reportId: String = checkNotNull(savedStateHandle["reportId"])

    val report: StateFlow<Report?> = repository.watchReport(reportId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Metrics that could be added: every metric not already in this report. */
    val addableMetrics: StateFlow<List<Metric>> =
        combine(repository.watchMetrics(), repository.watchReport(reportId)) { all, r ->
            val inReport = r?.metrics.orEmpty().map { it.id }.toSet()
            all.filter { it.id !in inReport }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _form = MutableStateFlow(ReportEditorForm())
    val form: StateFlow<ReportEditorForm> = _form.asStateFlow()

    init {
        // Seed once so later emissions (e.g. metric edits) don't clobber typing.
        viewModelScope.launch {
            val r = repository.watchReport(reportId).filterNotNull().first()
            _form.value = ReportEditorForm(name = r.name, includeNote = r.includeNote, isLoaded = true)
        }
    }

    fun setName(name: String) {
        _form.value = _form.value.copy(name = name, error = null)
    }

    fun setIncludeNote(include: Boolean) {
        _form.value = _form.value.copy(includeNote = include, error = null)
    }

    fun save(onSaved: () -> Unit) {
        val f = _form.value
        if (f.name.isBlank()) {
            _form.value = f.copy(error = "Name is required")
            return
        }
        viewModelScope.launch {
            repository.updateReport(reportId, f.name.trim(), f.includeNote)
            onSaved()
        }
    }

    fun addMetric(metricId: String) {
        viewModelScope.launch { repository.addMetricToReport(reportId, metricId) }
    }

    /** Creates a metric with default options, adds it here, and returns its id for editing. */
    fun createMetric(onCreated: (String) -> Unit) {
        viewModelScope.launch {
            val id = repository.createMetric("New metric", null, MetricKind.ORDINAL, NEW_METRIC_OPTIONS)
            repository.addMetricToReport(reportId, id)
            onCreated(id)
        }
    }

    fun removeMetric(metricId: String) {
        viewModelScope.launch { repository.removeMetricFromReport(reportId, metricId) }
    }

    fun moveMetric(metricId: String, delta: Int) {
        viewModelScope.launch { repository.moveMetricInReport(reportId, metricId, delta) }
    }

    fun deleteReport(onDeleted: () -> Unit) {
        viewModelScope.launch {
            repository.deleteReport(reportId)
            onDeleted()
        }
    }
}

// ── Metric editor ────────────────────────────────────────────────────

data class MetricForm(
    val name: String = "",
    val description: String = "",
    val kind: MetricKind = MetricKind.ORDINAL,
    val options: List<OptionDraft> = emptyList(),
    val isLoaded: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class MetricEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: ReportRepository,
) : ViewModel() {

    private val metricId: String = checkNotNull(savedStateHandle["metricId"])

    /** Names of the reports that ask this metric. */
    val usedIn: StateFlow<List<String>> = repository.watchReports()
        .map { reports -> reports.filter { r -> r.metrics.any { it.id == metricId } }.map { it.name } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _form = MutableStateFlow(MetricForm())
    val form: StateFlow<MetricForm> = _form.asStateFlow()

    init {
        viewModelScope.launch {
            val m = repository.watchMetric(metricId).filterNotNull().first()
            _form.value = MetricForm(
                name = m.name,
                description = m.description.orEmpty(),
                kind = m.kind,
                options = m.options.map { OptionDraft(it.id, it.label) },
                isLoaded = true,
            )
        }
    }

    fun update(transform: (MetricForm) -> MetricForm) {
        _form.value = transform(_form.value).copy(error = null)
    }

    fun save(onSaved: () -> Unit) {
        val f = _form.value
        val error = validateMetricDraft(f.name, f.options.map { it.label })
        if (error != null) {
            _form.value = f.copy(error = error)
            return
        }
        viewModelScope.launch {
            repository.saveMetric(
                metricId = metricId,
                name = f.name.trim(),
                description = f.description.trim().ifBlank { null },
                kind = f.kind,
                options = f.options.map { it.copy(label = it.label.trim()) },
            )
            onSaved()
        }
    }

    fun deleteMetric(onDeleted: () -> Unit) {
        viewModelScope.launch {
            repository.deleteMetric(metricId)
            onDeleted()
        }
    }
}
