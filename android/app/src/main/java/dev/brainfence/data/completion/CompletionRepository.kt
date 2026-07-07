package dev.brainfence.data.completion

import com.powersync.PowerSyncDatabase
import dev.brainfence.data.auth.SessionRepository
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
        val userId = sessionRepository.currentUser?.id
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
}
