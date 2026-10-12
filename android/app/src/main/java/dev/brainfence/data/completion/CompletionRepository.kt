package dev.brainfence.data.completion

import com.powersync.PowerSyncDatabase
import com.powersync.db.SqlCursor
import dev.brainfence.data.auth.SessionRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onStart
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CompletionRepository @Inject constructor(
    private val database: PowerSyncDatabase,
    private val sessionRepository: SessionRepository,
) {
    /**
     * Inserts a task_completion record into local SQLite.
     * PowerSync picks it up and syncs to Supabase automatically.
     *
     * @param taskId          The task being completed.
     * @param verificationData JSON string of proof data (defaults to empty for manual tasks).
     * @param occurrenceDate  The occurrence this completion satisfies (defaults to now).
     */
    suspend fun completeTask(
        taskId: String,
        verificationData: String = "{}",
        occurrenceDate: String = Instant.now().toString(),
    ) {
        val userId = sessionRepository.userIdForLocalWrites()
            ?: error("completeTask called while not authenticated")
        val now = Instant.now().toString()
        val id  = UUID.randomUUID().toString()

        // Idempotent per occurrence: at most one completion per one-off task
        // (ever) and per recurring task per local day. Auto-verifiers (GPS leave,
        // periodic re-checks, the due-check refresh, geofence EXIT) can all fire
        // for the same occurrence; the INSERT ... WHERE NOT EXISTS makes those
        // extra calls no-ops atomically, without a check-then-insert race.
        database.execute(
            sql = """
                INSERT INTO task_completions
                    (id, task_id, user_id, completed_at, occurrence_date, verification_data, created_at)
                SELECT ?, ?, ?, ?, ?, ?, ?
                WHERE NOT EXISTS (
                    SELECT 1 FROM task_completions existing
                    WHERE existing.task_id = ?
                      AND (
                        (SELECT t.recurrence_type FROM tasks t WHERE t.id = ?) IS NULL
                        OR date(existing.completed_at, 'localtime') = date('now', 'localtime')
                      )
                )
            """.trimIndent(),
            parameters = listOf(
                id, taskId, userId, now, occurrenceDate, verificationData, now,
                taskId, taskId,
            ),
        )
    }

    /**
     * Replace the proof data on today's completion of [taskId]. Used when a
     * completion is recorded early (e.g. a meditation short sit) and the
     * session carries on — the row stays, its recorded details are updated.
     */
    suspend fun updateTodaysVerificationData(taskId: String, verificationData: String) {
        database.execute(
            sql = """
                UPDATE task_completions SET verification_data = ?
                WHERE id = (
                    SELECT id FROM task_completions
                    WHERE task_id = ?
                      AND date(completed_at, 'localtime') = date('now', 'localtime')
                    ORDER BY completed_at DESC
                    LIMIT 1
                )
            """.trimIndent(),
            parameters = listOf(verificationData, taskId),
        )
    }

    /** Emits the verification_data JSON of today's completion of [taskId], or null. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun watchTodaysVerificationData(taskId: String): Flow<String?> =
        database.onChange(tables = setOf("task_completions"))
            .onStart { emit(emptySet()) }
            .flatMapLatest {
                flow {
                    val rows = database.getAll(
                        sql = """
                            SELECT verification_data FROM task_completions
                            WHERE task_id = ?
                              AND date(completed_at, 'localtime') = date('now', 'localtime')
                            ORDER BY completed_at DESC
                            LIMIT 1
                        """.trimIndent(),
                        parameters = listOf(taskId),
                    ) { cursor: SqlCursor -> cursor.getString(0) ?: "{}" }
                    emit(rows.firstOrNull())
                }
            }
}
