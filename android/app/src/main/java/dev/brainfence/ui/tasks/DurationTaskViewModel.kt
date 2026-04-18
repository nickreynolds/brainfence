package dev.brainfence.ui.tasks

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.brainfence.data.task.TaskRepository
import dev.brainfence.domain.model.Task
import dev.brainfence.service.DurationConfig
import dev.brainfence.service.DurationTimerManager
import dev.brainfence.service.DurationTimerState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class DurationTaskViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    taskRepository: TaskRepository,
    private val durationTimerManager: DurationTimerManager,
) : ViewModel() {

    private val taskId: String = checkNotNull(savedStateHandle["taskId"])

    val task: StateFlow<Task?> = taskRepository.watchActiveTasks()
        .map { tasks -> tasks.find { it.id == taskId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val durationConfig: StateFlow<DurationConfig?> = task
        .map { it?.verificationConfig?.let(DurationTimerManager::parseDurationConfig) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val timerState: StateFlow<DurationTimerState?> = durationTimerManager.timerStates
        .map { it[taskId] }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun startTimer() {
        val t = task.value ?: return
        val config = durationConfig.value ?: return
        durationTimerManager.startTimer(t.id, t.title, config.targetSeconds)
    }

    fun pauseTimer() {
        durationTimerManager.pauseTimer(taskId)
    }

    fun resumeTimer() {
        durationTimerManager.resumeTimer(taskId)
    }

    fun cancelTimer() {
        durationTimerManager.cancelTimer(taskId)
    }
}
