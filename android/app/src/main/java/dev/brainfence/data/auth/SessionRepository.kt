package dev.brainfence.data.auth

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SessionRepository @Inject constructor(
    private val supabase: SupabaseClient,
) {
    val authState = supabase.auth.sessionStatus
        .map { status ->
            when (status) {
                is SessionStatus.Authenticated      -> AuthState.SignedIn(status.session.user)
                is SessionStatus.NotAuthenticated   -> AuthState.SignedOut
                is SessionStatus.Initializing       -> AuthState.Loading
                else                               -> AuthState.SignedOut
            }
        }
        .stateIn(
            scope = CoroutineScope(Dispatchers.Default),
            started = SharingStarted.Eagerly,
            initialValue = AuthState.Loading,
        )

    val currentUser get() = supabase.auth.currentUserOrNull()

    suspend fun signIn(email: String, password: String) {
        supabase.auth.signInWith(Email) {
            this.email = email
            this.password = password
        }
    }

    suspend fun signUp(email: String, password: String) {
        supabase.auth.signUpWith(Email) {
            this.email = email
            this.password = password
        }
    }

    suspend fun signOut() {
        supabase.auth.signOut()
    }
}
