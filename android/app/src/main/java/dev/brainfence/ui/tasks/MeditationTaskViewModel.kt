package dev.brainfence.ui.tasks

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.brainfence.data.completion.CompletionRepository
import dev.brainfence.data.task.TaskRepository
import dev.brainfence.domain.model.Task
import dev.brainfence.service.MeditationConfig
import dev.brainfence.service.MeditationTimerManager
import dev.brainfence.service.MeditationTimerState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.json.JSONObject
import javax.inject.Inject

/** How today's meditation was completed, parsed from the completion's proof data. */
data class MeditationSessionSummary(
    val actualSeconds: Int,
    /** Finished at or after the short-sit threshold but before the full duration. */
    val isShortSit: Boolean,
)

@HiltViewModel
class MeditationTaskViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    taskRepository: TaskRepository,
    completionRepository: CompletionRepository,
    private val meditationTimerManager: MeditationTimerManager,
) : ViewModel() {

    private val taskId: String = checkNotNull(savedStateHandle["taskId"])

    val task: StateFlow<Task?> = taskRepository.watchActiveTasks()
        .map { tasks -> tasks.find { it.id == taskId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val meditationConfig: StateFlow<MeditationConfig?> = task
        .map { it?.verificationConfig?.let(MeditationTimerManager::parseMeditationConfig) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val timerState: StateFlow<MeditationTimerState?> = meditationTimerManager.timerStates
        .map { it[taskId] }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val todaysSession: StateFlow<MeditationSessionSummary?> =
        completionRepository.watchTodaysVerificationData(taskId)
            .map { json -> json?.let(::parseSessionSummary) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun startInAppTimer() = startInApp(shortSitIntent = false)

    /** Start the "middle way": a short sit that counts, with the rest offered after. */
    fun startShortSit() = startInApp(shortSitIntent = true)

    private fun startInApp(shortSitIntent: Boolean) {
        val t = task.value ?: return
        val config = meditationConfig.value ?: return
        if (shortSitIntent && config.shortSitSeconds <= 0) return
        meditationTimerManager.persistBellInterval(t.id, config.bellIntervalSeconds)
        meditationTimerManager.startInAppTimer(
            t.id,
            t.title,
            config.durationSeconds,
            config.bellIntervalSeconds,
            shortSitSeconds = config.shortSitSeconds,
            shortSitIntent = shortSitIntent,
        )
    }

    fun finishEarly() {
        meditationTimerManager.finishEarly(taskId)
    }

    fun startCompanionTracking() {
        val t = task.value ?: return
        val config = meditationConfig.value ?: return
        if (config.companionApps.isEmpty()) return
        meditationTimerManager.persistCompanionApps(t.id, config.companionApps)
        meditationTimerManager.startCompanionTracking(
            t.id,
            t.title,
            config.durationSeconds,
            config.companionApps,
        )
    }

    fun pauseTimer() {
        meditationTimerManager.pauseTimer(taskId)
    }

    fun resumeTimer() {
        meditationTimerManager.resumeTimer(taskId)
    }

    fun cancelTimer() {
        meditationTimerManager.cancelTimer(taskId)
    }

    fun onNavigatedAway() {
        meditationTimerManager.onNavigatedAway(taskId)
    }

    fun onReturnedToApp() {
        meditationTimerManager.onReturnedToApp(taskId)
    }
}

private fun parseSessionSummary(json: String): MeditationSessionSummary? = try {
    val obj = JSONObject(json)
    MeditationSessionSummary(
        actualSeconds = obj.optInt("actual_seconds", 0),
        isShortSit = obj.optBoolean("short_sit", false),
    )
} catch (_: Exception) {
    null
}
