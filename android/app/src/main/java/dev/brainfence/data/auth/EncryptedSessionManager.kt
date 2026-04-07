package dev.brainfence.data.auth

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class EncryptedSessionManager(context: Context) : SessionManager {

    private val prefs = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            "brainfence_session",
            masterKey,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override suspend fun saveSession(session: UserSession) {
        prefs.edit().putString(KEY, Json.encodeToString(session)).apply()
    }

    override suspend fun loadSession(): UserSession? {
        val json = prefs.getString(KEY, null) ?: return null
        return runCatching { Json.decodeFromString<UserSession>(json) }.getOrNull()
    }

    override suspend fun deleteSession() {
        prefs.edit().remove(KEY).apply()
    }

    companion object {
        private const val KEY = "session"
    }
}
