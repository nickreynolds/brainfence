package dev.brainfence.ui.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.brainfence.data.auth.AccountDeletion
import dev.brainfence.data.auth.SessionRepository
import dev.brainfence.ui.onboarding.OnboardingGate
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val sessionRepository: SessionRepository,
    private val accountDeletion: AccountDeletion,
    private val onboardingGate: OnboardingGate,
) : ViewModel() {

    val email: String? get() = sessionRepository.currentUser?.email

    fun signOut() {
        // A different account may sign in next: decide its onboarding again.
        onboardingGate.reset()
        viewModelScope.launch { sessionRepository.signOut() }
    }

    /** Deletes the account; [onDone] gets null, or why it couldn't be deleted. */
    fun deleteAccount(onDone: (error: String?) -> Unit) {
        viewModelScope.launch {
            val error = try {
                accountDeletion.delete()
                onboardingGate.reset()
                null
            } catch (e: Exception) {
                Log.e("SettingsViewModel", "Account deletion failed", e)
                e.message ?: "Check your connection and try again."
            }
            onDone(error)
        }
    }
}
