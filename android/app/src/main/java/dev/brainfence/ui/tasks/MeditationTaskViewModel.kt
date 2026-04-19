package dev.brainfence.ui.tasks

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.brainfence.data.task.TaskRepository
import dev.brainfence.domain.model.Task
import dev.brainfence.service.MeditationConfig
import dev.brainfence.service.MeditationTimerManager
import dev.brainfence.service.MeditationTimerState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class MeditationTaskViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    taskRepository: TaskRepository,
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

    fun startInAppTimer() {
        val t = task.value ?: return
        val config = meditationConfig.value ?: return
        meditationTimerManager.persistBellInterval(t.id, config.bellIntervalSeconds)
        meditationTimerManager.startInAppTimer(
            t.id,
            t.title,
            config.durationSeconds,
            config.bellIntervalSeconds,
        )
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
