package dev.brainfence.data.db

import com.powersync.PowerSyncDatabase
import dev.brainfence.data.auth.AuthState
import dev.brainfence.data.auth.SessionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

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
        scope.launch {
            sessionRepository.authState.collect { state ->
                when (state) {
                    is AuthState.SignedIn  -> database.connect(connector)
                    is AuthState.SignedOut -> database.disconnect()
                    is AuthState.Loading   -> Unit
                }
            }
        }
    }
}
