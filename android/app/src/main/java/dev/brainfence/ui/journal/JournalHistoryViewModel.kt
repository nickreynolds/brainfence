package dev.brainfence.ui.journal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.brainfence.data.journal.JournalRepository
import dev.brainfence.domain.model.JournalEntry
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class JournalHistoryViewModel @Inject constructor(
    journalRepository: JournalRepository,
) : ViewModel() {
    val entries: StateFlow<List<JournalEntry>> = journalRepository.watchJournalEntries()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
