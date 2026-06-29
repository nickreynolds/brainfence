package dev.brainfence.data.auth

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.KeyStore

class EncryptedSessionManager(context: Context) : SessionManager {

    private val prefs = openPrefs(context)

    private fun openPrefs(context: Context) = try {
        createEncryptedPrefs(context)
    } catch (t: Throwable) {
        // The Tink keyset stored in SharedPreferences can become unreadable
        // when the Android Keystore master key is rotated or wiped (reinstalls,
        // device restores, keystore-affecting OS updates). When that happens
        // EncryptedSharedPreferences throws AEADBadTagException and the app
        // can't start. Recover by wiping the keyset + master key and rebuilding.
        Log.w(TAG, "EncryptedSharedPreferences unreadable, resetting session store", t)
        context.deleteSharedPreferences(PREFS_NAME)
        runCatching {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                .deleteEntry(MASTER_KEY_ALIAS)
        }
        createEncryptedPrefs(context)
    }

    private fun createEncryptedPrefs(context: Context) =
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )

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
        private const val TAG = "EncryptedSession"
        private const val KEY = "session"
        private const val PREFS_NAME = "brainfence_session"
        private const val MASTER_KEY_ALIAS = MasterKey.DEFAULT_MASTER_KEY_ALIAS
    }
}
