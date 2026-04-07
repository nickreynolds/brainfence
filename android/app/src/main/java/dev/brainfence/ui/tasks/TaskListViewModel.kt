package dev.brainfence.ui.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.brainfence.data.auth.SessionRepository
import dev.brainfence.data.completion.CompletionRepository
import dev.brainfence.data.task.TaskRepository
import dev.brainfence.domain.model.Task
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TaskListViewModel @Inject constructor(
    private val taskRepository: TaskRepository,
    private val completionRepository: CompletionRepository,
    private val sessionRepository: SessionRepository,
) : ViewModel() {

    val tasks = taskRepository.watchActiveTasks()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList<Task>(),
        )

    // The task currently awaiting confirmation; null = no dialog shown
    private val _pendingTask = MutableStateFlow<Task?>(null)
    val pendingTask = _pendingTask.asStateFlow()

    fun requestComplete(task: Task) {
        if (!task.completedToday) _pendingTask.value = task
    }

    fun confirmComplete() {
        val task = _pendingTask.value ?: return
        _pendingTask.value = null
        viewModelScope.launch {
            completionRepository.completeTask(taskId = task.id)
        }
    }

    fun dismissComplete() {
        _pendingTask.value = null
    }

    fun signOut() {
        viewModelScope.launch { sessionRepository.signOut() }
    }
}
