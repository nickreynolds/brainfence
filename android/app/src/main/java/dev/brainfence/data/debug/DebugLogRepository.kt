package dev.brainfence.data.debug

import com.powersync.PowerSyncDatabase
import com.powersync.db.SqlCursor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class DebugLogEntry(
    val id: String,
    val timestamp: String,
    val category: String,
    val message: String,
    val data: String?,
    val lat: Double?,
    val lng: Double?,
    val accuracyM: Float?,
)

/**
 * Local-only debug log storage for diagnosing geofence, service, and location issues.
 * Uses a separate table in the PowerSync SQLite DB that is NOT synced.
 */
@Singleton
class DebugLogRepository @Inject constructor(
    private val database: PowerSyncDatabase,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val ready = CompletableDeferred<Unit>()
    private val _refresh = MutableSharedFlow<Unit>(replay = 1)

    init {
        scope.launch {
            database.execute(
                """
                CREATE TABLE IF NOT EXISTS local_debug_logs (
                    id TEXT PRIMARY KEY,
                    timestamp TEXT NOT NULL,
                    category TEXT NOT NULL,
                    message TEXT NOT NULL,
                    data TEXT,
                    lat REAL,
                    lng REAL,
                    accuracy_m REAL
                )
                """.trimIndent(),
            )
            // Prune entries older than 48 hours
            database.execute(
                sql = "DELETE FROM local_debug_logs WHERE timestamp < ?",
                parameters = listOf(Instant.now().minusSeconds(48 * 3600).toString()),
            )
            ready.complete(Unit)
            _refresh.emit(Unit)
        }
    }

    suspend fun log(
        category: String,
        message: String,
        data: String? = null,
        lat: Double? = null,
        lng: Double? = null,
        accuracyM: Float? = null,
    ) {
        ready.await()
        val id = UUID.randomUUID().toString()
        val now = Instant.now().toString()
        database.execute(
            sql = """
                INSERT INTO local_debug_logs (id, timestamp, category, message, data, lat, lng, accuracy_m)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            parameters = listOf(id, now, category, message, data, lat, lng, accuracyM?.toDouble()),
        )
        _refresh.emit(Unit)
    }

    fun watchLogs(category: String? = null): Flow<List<DebugLogEntry>> =
        _refresh.onStart { emit(Unit) }.map {
            ready.await()
            val sql = buildString {
                append("SELECT id, timestamp, category, message, data, lat, lng, accuracy_m FROM local_debug_logs")
                if (category != null) append(" WHERE category = ?")
                append(" ORDER BY timestamp DESC LIMIT 500")
            }
            val params = if (category != null) listOf<Any>(category) else emptyList()
            database.getAll(sql, parameters = params, mapper = ::mapEntry)
        }

    fun watchLocationLogs(): Flow<List<DebugLogEntry>> =
        _refresh.onStart { emit(Unit) }.map {
            ready.await()
            database.getAll(
                sql = """
                    SELECT id, timestamp, category, message, data, lat, lng, accuracy_m
                    FROM local_debug_logs
                    WHERE lat IS NOT NULL AND lng IS NOT NULL
                    ORDER BY timestamp DESC
                    LIMIT 500
                """.trimIndent(),
                mapper = ::mapEntry,
            )
        }

    suspend fun clearLogs() {
        ready.await()
        database.execute("DELETE FROM local_debug_logs")
        _refresh.emit(Unit)
    }

    private fun mapEntry(cursor: SqlCursor): DebugLogEntry = DebugLogEntry(
        id = cursor.getString(0)!!,
        timestamp = cursor.getString(1)!!,
        category = cursor.getString(2)!!,
        message = cursor.getString(3)!!,
        data = cursor.getString(4),
        lat = cursor.getString(5)?.toDoubleOrNull(),
        lng = cursor.getString(6)?.toDoubleOrNull(),
        accuracyM = cursor.getString(7)?.toFloatOrNull(),
    )
}
