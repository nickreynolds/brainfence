package dev.brainfence.data.auth

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.parseSessionFromFragment
import io.github.jan.supabase.auth.status.SessionStatus
import android.util.Log
import dev.brainfence.domain.auth.AuthCallback
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    /**
     * The signed-in user's id for local database writes. Falls back to the
     * persisted session when the in-memory status isn't Authenticated (e.g. a
     * token refresh is pending or failed offline): a local PowerSync write only
     * needs the id, and it uploads once the session is valid again.
     * Null only when no one has signed in on this device.
     */
    suspend fun userIdForLocalWrites(): String? =
        currentUser?.id ?: supabase.auth.sessionManager.loadSession()?.user?.id

    suspend fun signIn(email: String, password: String) {
        supabase.auth.signInWith(Email) {
            this.email = email
            this.password = password
        }
    }

    /** Creates an account. Returns true when the email must be confirmed before signing in. */
    suspend fun signUp(email: String, password: String): Boolean {
        supabase.auth.signUpWith(Email, redirectUrl = AuthCallback.URL) {
            this.email = email
            this.password = password
        }
        return supabase.auth.currentSessionOrNull() == null
    }

    private val _authNotice = MutableStateFlow<String?>(null)

    /** For the sign-in screen, from the last email link. */
    val authNotice: StateFlow<String?> = _authNotice.asStateFlow()

    fun clearAuthNotice() {
        _authNotice.value = null
    }

    /**
     * Handles a brainfence://auth-callback email link; false for other URLs.
     * Supabase confirmed the email before redirecting, so signing in here is a
     * bonus: it fails when the link is opened on a different device than the
     * sign-up (shared/CONTRACTS.md, Email links).
     */
    suspend fun handleAuthCallback(url: String): Boolean {
        val link = AuthCallback.parse(url) ?: return false
        try {
            when (link) {
                is AuthCallback.Error -> _authNotice.value = AuthCallback.errorNotice(link.message)
                is AuthCallback.Code -> supabase.auth.exchangeCodeForSession(link.code)
                is AuthCallback.Tokens -> supabase.auth.importSession(
                    supabase.auth.parseSessionFromFragment(url.substringAfter('#')),
                )
                AuthCallback.None -> _authNotice.value = AuthCallback.CONFIRMED_NOTICE
            }
        } catch (e: Exception) {
            Log.i("SessionRepository", "Email link didn't sign in: ${e.message}")
            _authNotice.value = AuthCallback.CONFIRMED_NOTICE
        }
        return true
    }

    suspend fun signOut() {
        supabase.auth.signOut()
    }

    /**
     * Nudge the auth module to retry session restoration.
     * Useful when the token expired during Doze and the initial refresh failed
     * due to network restrictions — by the time a geofence wakes us, network
     * is likely available again.
     */
    suspend fun refreshSession() {
        supabase.auth.refreshCurrentSession()
    }
}
