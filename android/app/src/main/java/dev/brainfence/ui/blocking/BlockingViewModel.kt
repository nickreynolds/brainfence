package dev.brainfence.ui.blocking

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.brainfence.data.completion.CompletionRepository
import dev.brainfence.data.task.TaskRepository
import dev.brainfence.domain.model.BlockingRule
import dev.brainfence.domain.model.Task
import dev.brainfence.service.BrainfenceService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BlockingUiState(
    val blockedPackage: String = "",
    val appLabel: String = "",
    val rules: List<BlockingRule> = emptyList(),
    val requiredTasks: List<Task> = emptyList(),
    val allTasksCompleted: Boolean = false,
    val isBlocked: Boolean = true,
)

@HiltViewModel
class BlockingViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val taskRepository: TaskRepository,
    private val completionRepository: CompletionRepository,
) : ViewModel() {

    private val blockedPackage: String =
        savedStateHandle[BlockingActivity.EXTRA_BLOCKED_PACKAGE] ?: ""

    private val _appLabel = MutableStateFlow("")
    fun setAppLabel(label: String) { _appLabel.value = label }

    val uiState: StateFlow<BlockingUiState> =
        combine(
            BrainfenceService.blockingState,
            taskRepository.watchActiveTasks(),
            _appLabel,
        ) { blockingState, allTasks, appLabel ->
            val rules = blockingState.rulesByApp[blockedPackage].orEmpty()
            val requiredTaskIds = rules.flatMap { it.conditionTaskIds }.toSet()
            val requiredTasks = allTasks.filter { it.id in requiredTaskIds }
            val allCompleted = requiredTasks.isNotEmpty() && requiredTasks.all { it.completedToday }
            val isBlocked = blockedPackage in blockingState.blockedApps

            BlockingUiState(
                blockedPackage = blockedPackage,
                appLabel = appLabel,
                rules = rules,
                requiredTasks = requiredTasks,
                allTasksCompleted = allCompleted,
                isBlocked = isBlocked,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = BlockingUiState(blockedPackage = blockedPackage),
        )

    fun completeTask(taskId: String) {
        viewModelScope.launch {
            completionRepository.completeTask(taskId)
        }
    }
}
