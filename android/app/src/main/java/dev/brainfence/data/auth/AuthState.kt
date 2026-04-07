package dev.brainfence.data.auth

import io.github.jan.supabase.auth.user.UserInfo

sealed class AuthState {
    data object Loading : AuthState()
    data object SignedOut : AuthState()
    data class SignedIn(val user: UserInfo?) : AuthState()
}
