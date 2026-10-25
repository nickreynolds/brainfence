package dev.brainfence.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.brainfence.data.auth.AuthState
import dev.brainfence.data.auth.SessionRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AuthUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    /** "Check your email", or the outcome of an email link. */
    val notice: String? = null,
)

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val sessionRepository: SessionRepository,
) : ViewModel() {

    val authState = sessionRepository.authState

    init {
        // Email confirmation links (handled in MainActivity) report here.
        viewModelScope.launch {
            sessionRepository.authNotice.collect { notice ->
                if (notice != null) {
                    _uiState.update { it.copy(notice = notice, error = null) }
                    sessionRepository.clearAuthNotice()
                }
            }
        }
    }

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState = _uiState.asStateFlow()

    fun signIn(email: String, password: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null, notice = null) }
            runCatching { sessionRepository.signIn(email, password) }
                .onSuccess { _uiState.update { it.copy(isLoading = false) } }
                .onFailure { e -> _uiState.update { it.copy(isLoading = false, error = e.message) } }
        }
    }

    /** [onNeedsConfirmation] runs when the account waits for its email to be confirmed. */
    fun signUp(email: String, password: String, onNeedsConfirmation: () -> Unit) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null, notice = null) }
            runCatching { sessionRepository.signUp(email, password) }
                .onSuccess { needsConfirmation ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            notice = if (needsConfirmation) "Check your email to confirm your account, then sign in." else null,
                        )
                    }
                    if (needsConfirmation) onNeedsConfirmation()
                }
                .onFailure { e -> _uiState.update { it.copy(isLoading = false, error = e.message) } }
        }
    }

    fun signOut() {
        viewModelScope.launch { sessionRepository.signOut() }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
