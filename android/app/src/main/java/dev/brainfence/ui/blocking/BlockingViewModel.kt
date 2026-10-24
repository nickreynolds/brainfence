package dev.brainfence.ui.blocking

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.brainfence.data.completion.CompletionRepository
import dev.brainfence.data.task.TaskRepository
import dev.brainfence.domain.blocking.BlockingState
import dev.brainfence.domain.model.BlockingRule
import dev.brainfence.domain.model.Task
import dev.brainfence.service.BrainfenceService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the overlay is blocking. */
sealed interface BlockTarget {
    /** A blocked app. */
    data class App(val packageName: String, val label: String) : BlockTarget
    /** A blocked website, open in [browserLabel]. */
    data class Website(val domain: String, val browserLabel: String) : BlockTarget
    /** A browser whose address bar can't be read, blocked while any website is. */
    data class UnsupportedBrowser(val packageName: String, val label: String) : BlockTarget
}

data class BlockingUiState(
    val headline: String = "",
    val rules: List<BlockingRule> = emptyList(),
    val requiredTasks: List<Task> = emptyList(),
    val allTasksCompleted: Boolean = false,
    val isBlocked: Boolean = true,
)

@HiltViewModel
class BlockingViewModel @Inject constructor(
    private val taskRepository: TaskRepository,
    private val completionRepository: CompletionRepository,
) : ViewModel() {

    private val target = MutableStateFlow<BlockTarget?>(null)

    /** Set from the activity's intent (again on onNewIntent, when the overlay is reused). */
    fun setTarget(value: BlockTarget) { target.value = value }

    val uiState: StateFlow<BlockingUiState> =
        combine(
            BrainfenceService.blockingState,
            taskRepository.watchActiveTasks(),
            target,
        ) { blockingState, allTasks, target ->
            target ?: return@combine BlockingUiState()
            val (rules, unmetTaskIds, isBlocked) = blockedBy(blockingState, target)
            val requiredTasks = allTasks.filter { it.id in unmetTaskIds }
            BlockingUiState(
                headline = when (target) {
                    is BlockTarget.App -> "${target.label} is blocked"
                    is BlockTarget.Website -> "${target.domain} is blocked"
                    is BlockTarget.UnsupportedBrowser -> "${target.label} is blocked while websites are blocked"
                },
                rules = rules,
                requiredTasks = requiredTasks,
                allTasksCompleted = requiredTasks.isEmpty(),
                isBlocked = isBlocked,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = BlockingUiState(),
        )

    private fun blockedBy(state: BlockingState, target: BlockTarget): Triple<List<BlockingRule>, Set<String>, Boolean> =
        when (target) {
            is BlockTarget.App -> Triple(
                state.rulesByApp[target.packageName].orEmpty(),
                state.unmetTaskIdsByApp[target.packageName].orEmpty(),
                target.packageName in state.blockedApps,
            )
            is BlockTarget.Website -> Triple(
                state.rulesByDomain[target.domain].orEmpty(),
                state.unmetTaskIdsByDomain[target.domain].orEmpty(),
                target.domain in state.blockedDomains,
            )
            is BlockTarget.UnsupportedBrowser -> Triple(
                state.rulesByDomain.values.flatten().distinctBy { it.id },
                state.unmetTaskIdsByDomain.values.flatten().toSet(),
                state.blockedDomains.isNotEmpty(),
            )
        }

    fun completeTask(taskId: String) {
        viewModelScope.launch {
            completionRepository.completeTask(taskId)
        }
    }
}
