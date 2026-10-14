package dev.brainfence.ui.report

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.brainfence.data.completion.CompletionRepository
import dev.brainfence.data.report.ReportRepository
import dev.brainfence.data.task.TaskRepository
import dev.brainfence.domain.model.Report
import dev.brainfence.domain.model.ReportSubmission
import dev.brainfence.domain.model.Task
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONObject
import javax.inject.Inject

/** The report id a report task or routine report step points at, from its config JSON. */
fun reportIdFromConfig(configJson: String?): String? =
    runCatching { JSONObject(configJson ?: "{}").optString("report_id") }
        .getOrNull()?.takeIf { it.isNotBlank() }

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ReportTaskViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    taskRepository: TaskRepository,
    private val reportRepository: ReportRepository,
    private val completionRepository: CompletionRepository,
) : ViewModel() {

    private val taskId: String = checkNotNull(savedStateHandle["taskId"])

    val task: StateFlow<Task?> = taskRepository.watchActiveTasks()
        .map { tasks -> tasks.find { it.id == taskId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Null while loading or if the task's report no longer exists. */
    val report: StateFlow<Report?> = task
        .map { reportIdFromConfig(it?.verificationConfig) }
        .distinctUntilChanged()
        .flatMapLatest { id -> if (id == null) flowOf(null) else reportRepository.watchReport(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val todaysSubmission: StateFlow<ReportSubmission?> = reportRepository.watchTodaysTaskSubmission(taskId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _isSubmitting = MutableStateFlow(false)
    val isSubmitting: StateFlow<Boolean> = _isSubmitting.asStateFlow()

    fun submit(answers: Map<String, String>, note: String, onDone: () -> Unit) {
        val r = report.value ?: return
        if (_isSubmitting.value) return
        _isSubmitting.value = true
        viewModelScope.launch {
            try {
                val submissionId = reportRepository.submit(r, answers, note, taskId, routineStepId = null)
                completionRepository.completeTask(
                    taskId = taskId,
                    verificationData = JSONObject()
                        .put("method", "report")
                        .put("submission_id", submissionId)
                        .toString(),
                )
                onDone()
            } finally {
                _isSubmitting.value = false
            }
        }
    }
}
