package dev.brainfence.ui.contemplation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.brainfence.data.contemplation.ContemplationLibrary
import dev.brainfence.data.contemplation.ContemplationRepository
import dev.brainfence.data.contemplation.ContemplationSettings
import dev.brainfence.data.contemplation.ContemplationSettingsRepository
import dev.brainfence.domain.model.ContemplationDeck
import dev.brainfence.domain.model.ContemplationDelivery
import dev.brainfence.domain.recurrence.parseTaskTime
import dev.brainfence.service.ContemplationNotifier
import dev.brainfence.service.ContemplationScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/** Editable schedule fields; text so partial input doesn't fight the user. */
data class ContemplationScheduleForm(
    val enabled: Boolean,
    val perDay: String,
    val windowStart: String,
    val windowEnd: String,
    val message: String? = null,
    val error: String? = null,
)

@HiltViewModel
class ContemplationSettingsViewModel @Inject constructor(
    private val settingsRepository: ContemplationSettingsRepository,
    private val repository: ContemplationRepository,
    private val scheduler: ContemplationScheduler,
    private val notifier: ContemplationNotifier,
) : ViewModel() {

    private val _form = MutableStateFlow(settingsRepository.get().toForm())
    val form: StateFlow<ContemplationScheduleForm> = _form.asStateFlow()

    val decks: StateFlow<List<ContemplationDeck>> = repository.watchDecks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Starter decks the user hasn't added yet. */
    val availableLibraryDecks: StateFlow<List<ContemplationLibrary.LibraryDeck>> = repository.watchDecks()
        .map { decks ->
            val added = decks.mapNotNull { it.libraryKey }.toSet()
            ContemplationLibrary.decks.filter { it.key !in added }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val recentDeliveries: StateFlow<List<ContemplationDelivery>> = repository.watchRecentDeliveries()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Toggling takes effect immediately, using the last *saved* schedule. */
    fun setEnabled(enabled: Boolean) {
        settingsRepository.set(settingsRepository.get().copy(enabled = enabled))
        scheduler.reschedule()
        _form.value = _form.value.copy(enabled = enabled, message = null, error = null)
    }

    fun setPerDay(value: String) {
        _form.value = _form.value.copy(perDay = value.filter(Char::isDigit), message = null)
    }

    fun setWindowStart(value: String) {
        _form.value = _form.value.copy(windowStart = value, message = null)
    }

    fun setWindowEnd(value: String) {
        _form.value = _form.value.copy(windowEnd = value, message = null)
    }

    fun saveSchedule() {
        val f = _form.value
        val perDay = f.perDay.toIntOrNull()
        val start = parseTaskTime(f.windowStart)
        val end = parseTaskTime(f.windowEnd)
        val error = when {
            perDay == null || perDay !in 1..MAX_PER_DAY -> "Per day must be between 1 and $MAX_PER_DAY"
            start == null || end == null -> "Times must be HH:MM (e.g. 08:00)"
            !end.isAfter(start) -> "The window must end after it starts"
            else -> null
        }
        if (error != null) {
            _form.value = f.copy(error = error, message = null)
            return
        }
        settingsRepository.set(ContemplationSettings(f.enabled, perDay!!, start!!, end!!))
        scheduler.reschedule()
        _form.value = settingsRepository.get().toForm().copy(message = "Schedule saved")
    }

    fun clearError() {
        _form.value = _form.value.copy(error = null)
    }

    fun addFromLibrary(key: String) {
        val libraryDeck = ContemplationLibrary.decks.firstOrNull { it.key == key } ?: return
        viewModelScope.launch { repository.addFromLibrary(libraryDeck) }
    }

    fun setDeckEnabled(deckId: String, enabled: Boolean) {
        viewModelScope.launch { repository.setDeckEnabled(deckId, enabled) }
    }

    fun createDeck(onCreated: (String) -> Unit) {
        viewModelScope.launch { onCreated(repository.createDeck("New deck")) }
    }

    /** Deliver one immediately — handy for previewing a deck. */
    fun sendNow() {
        viewModelScope.launch {
            val sent = notifier.deliver()
            _form.value = _form.value.copy(
                message = if (sent) "Sent" else null,
                error = if (sent) null
                        else "Nothing sent — needs an enabled deck with items and notification permission",
            )
        }
    }

    private fun ContemplationSettings.toForm() = ContemplationScheduleForm(
        enabled = enabled,
        perDay = perDay.toString(),
        windowStart = windowStart.format(TIME_FORMAT),
        windowEnd = windowEnd.format(TIME_FORMAT),
    )

    private companion object {
        const val MAX_PER_DAY = 24
        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}
