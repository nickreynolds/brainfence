package dev.brainfence.data.db

import android.util.Log
import com.powersync.PowerSyncDatabase
import dev.brainfence.data.auth.AuthState
import dev.brainfence.data.auth.SessionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SyncManager"

/**
 * Observes auth state and connects / disconnects the PowerSync database accordingly.
 * Initialized once from BrainfenceApp.onCreate().
 */
@Singleton
class SyncManager @Inject constructor(
    private val database: PowerSyncDatabase,
    private val connector: SupabasePowerSyncConnector,
    private val sessionRepository: SessionRepository,
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    fun initialize() {
        Log.d(TAG, "SyncManager initialized")
        scope.launch {
            sessionRepository.authState.collect { state ->
                Log.d(TAG, "Auth state changed: $state")
                when (state) {
                    is AuthState.SignedIn  -> {
                        Log.d(TAG, "Connecting PowerSync…")
                        try {
                            database.connect(connector)
                            Log.d(TAG, "PowerSync connected")
                        } catch (e: Exception) {
                            Log.e(TAG, "PowerSync connect failed", e)
                        }
                    }
                    is AuthState.SignedOut -> {
                        Log.d(TAG, "Disconnecting PowerSync")
                        database.disconnect()
                    }
                    is AuthState.Loading   -> Unit
                }
            }
        }
    }
}
