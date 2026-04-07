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

        database.execute(
            sql = """
                INSERT INTO task_completions
                    (id, task_id, user_id, completed_at, occurrence_date, verification_data, created_at)
                VALUES
                    (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            parameters = listOf(id, taskId, userId, now, occurrenceDate, verificationData, now),
        )
    }
}
