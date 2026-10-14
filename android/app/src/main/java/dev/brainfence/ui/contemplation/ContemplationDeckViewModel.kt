package dev.brainfence.ui.contemplation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.brainfence.data.contemplation.ContemplationRepository
import dev.brainfence.domain.model.Contemplation
import dev.brainfence.domain.model.ContemplationDeck
import dev.brainfence.domain.model.ContemplationResponseType
import dev.brainfence.domain.model.DeckOrdering
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DeckForm(
    val name: String = "",
    val description: String = "",
    val ordering: DeckOrdering = DeckOrdering.SEQUENTIAL,
    val responseType: ContemplationResponseType = ContemplationResponseType.NONE,
    val responseLabel: String = "",
    val weight: String = "1",
    val isLoaded: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class ContemplationDeckViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: ContemplationRepository,
) : ViewModel() {

    private val deckId: String = checkNotNull(savedStateHandle["deckId"])

    val deck: StateFlow<ContemplationDeck?> = repository.watchDeck(deckId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val items: StateFlow<List<Contemplation>> = repository.watchItems(deckId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _form = MutableStateFlow(DeckForm())
    val form: StateFlow<DeckForm> = _form.asStateFlow()

    init {
        // Seed the form once; later DB emissions (e.g. item edits) must not
        // clobber unsaved field edits.
        viewModelScope.launch {
            val d = repository.watchDeck(deckId).filterNotNull().first()
            _form.value = DeckForm(
                name = d.name,
                description = d.description.orEmpty(),
                ordering = d.ordering,
                responseType = d.responseType,
                responseLabel = d.responseLabel.orEmpty(),
                weight = d.weight.toString(),
                isLoaded = true,
            )
        }
    }

    fun update(transform: (DeckForm) -> DeckForm) {
        _form.value = transform(_form.value).copy(error = null)
    }

    fun save(onSaved: () -> Unit) {
        val f = _form.value
        val weight = f.weight.toIntOrNull()
        val error = when {
            f.name.isBlank() -> "Name is required"
            weight == null || weight < 1 -> "Frequency must be a whole number of at least 1"
            else -> null
        }
        if (error != null) {
            _form.value = f.copy(error = error)
            return
        }
        viewModelScope.launch {
            repository.updateDeck(
                deckId = deckId,
                name = f.name.trim(),
                description = f.description.trim().ifBlank { null },
                ordering = f.ordering,
                responseType = f.responseType,
                responseLabel = f.responseLabel.trim().ifBlank { null },
                weight = weight!!,
            )
            onSaved()
        }
    }

    fun deleteDeck(onDeleted: () -> Unit) {
        viewModelScope.launch {
            repository.deleteDeck(deckId)
            onDeleted()
        }
    }

    /** Adds when [itemId] is null, otherwise updates. Blank body is ignored. */
    fun saveItem(itemId: String?, title: String, body: String, attribution: String) {
        if (body.isBlank()) return
        val t = title.trim().ifBlank { null }
        val a = attribution.trim().ifBlank { null }
        viewModelScope.launch {
            if (itemId == null) repository.addItem(deckId, t, body.trim(), a)
            else repository.updateItem(itemId, t, body.trim(), a)
        }
    }

    fun deleteItem(itemId: String) {
        viewModelScope.launch { repository.deleteItem(itemId) }
    }
}
