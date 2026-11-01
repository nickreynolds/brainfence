package dev.brainfence.data.feedback

import com.powersync.PowerSyncDatabase
import dev.brainfence.BuildConfig
import dev.brainfence.data.auth.SessionRepository
import dev.brainfence.domain.feedback.Feedback
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sends feedback through the insert-only `feedback` table: queued locally,
 * uploaded when online, never synced back. Same as
 * desktop-app/src/shared/repos/feedback.ts. See shared/CONTRACTS.md (Feedback).
 */
@Singleton
class FeedbackRepository @Inject constructor(
    private val database: PowerSyncDatabase,
    private val sessionRepository: SessionRepository,
) {
    suspend fun send(message: String, source: Feedback.Source) {
        val text = Feedback.normalize(message) ?: error("Write something first (up to ${Feedback.MAX_LENGTH} characters).")
        val userId = sessionRepository.userIdForLocalWrites() ?: error("Sign in to send feedback.")
        database.execute(
            sql = """
                INSERT INTO feedback (id, user_id, message, platform, app_version, source, created_at)
                VALUES (?, ?, ?, 'android', ?, ?, ?)
            """.trimIndent(),
            parameters = listOf(
                UUID.randomUUID().toString(), userId, text, BuildConfig.VERSION_NAME, source.key, Instant.now().toString(),
            ),
        )
    }
}
