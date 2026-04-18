package dev.brainfence.ui.tasks

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.brainfence.data.task.TaskRepository
import dev.brainfence.domain.model.Task
import dev.brainfence.service.GpsConfig
import dev.brainfence.service.GpsVerificationManager
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class GpsTaskViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    taskRepository: TaskRepository,
    gpsVerificationManager: GpsVerificationManager,
) : ViewModel() {

    private val taskId: String = checkNotNull(savedStateHandle["taskId"])

    val task: StateFlow<Task?> = taskRepository.watchActiveTasks()
        .map { tasks -> tasks.find { it.id == taskId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val gpsConfig: StateFlow<GpsConfig?> = task
        .map { it?.verificationConfig?.let(GpsVerificationManager::parseGpsConfig) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val isTracking: StateFlow<Boolean> = gpsVerificationManager.trackedTaskIds
        .map { taskId in it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
}
