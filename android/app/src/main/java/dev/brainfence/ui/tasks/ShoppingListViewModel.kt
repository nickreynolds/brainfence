package dev.brainfence.ui.tasks

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.brainfence.data.shopping.ShoppingRepository
import dev.brainfence.data.task.TaskRepository
import dev.brainfence.domain.model.ShoppingItem
import dev.brainfence.domain.model.Task
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ShoppingListViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    taskRepository: TaskRepository,
    private val shoppingRepository: ShoppingRepository,
) : ViewModel() {

    private val taskId: String = checkNotNull(savedStateHandle["taskId"])

    val task: StateFlow<Task?> = taskRepository.watchActiveTasks()
        .map { tasks -> tasks.find { it.id == taskId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val openItems: StateFlow<List<ShoppingItem>> = shoppingRepository.watchOpenItems(taskId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun addItem(title: String) {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch { shoppingRepository.addItem(taskId, trimmed) }
    }

    /** Check off an item; returns via [onDone] so the UI can offer an undo. */
    fun checkOff(item: ShoppingItem, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            shoppingRepository.completeItem(item.id)
            onDone()
        }
    }

    fun undoCheckOff(itemId: String) {
        viewModelScope.launch { shoppingRepository.uncompleteItem(itemId) }
    }

    fun deleteItem(itemId: String) {
        viewModelScope.launch { shoppingRepository.deleteItem(itemId) }
    }
}
