package dev.brainfence.data.journal

import com.powersync.PowerSyncDatabase
import com.powersync.db.SqlCursor
import dev.brainfence.domain.model.JournalEntry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onStart
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

private val JOURNAL_ENTRIES_SQL = """
    SELECT
        tc.id,
        tc.task_id,
        t.title,
        tc.verification_data,
        tc.completed_at
    FROM task_completions tc
    JOIN tasks t ON t.id = tc.task_id
    WHERE t.task_type = 'journal'
    ORDER BY tc.completed_at DESC
""".trimIndent()

@Singleton
class JournalRepository @Inject constructor(
    private val database: PowerSyncDatabase,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    fun watchJournalEntries(): Flow<List<JournalEntry>> =
        database.onChange(tables = setOf("tasks", "task_completions"))
            .onStart { emit(emptySet()) }
            .flatMapLatest {
                flow {
                    emit(database.getAll(JOURNAL_ENTRIES_SQL, mapper = ::mapEntry))
                }
            }

    private fun mapEntry(cursor: SqlCursor): JournalEntry {
        val verificationData = cursor.getString(3) ?: "{}"
        val text = try {
            JSONObject(verificationData).optString("text", "")
        } catch (_: Exception) {
            ""
        }
        return JournalEntry(
            id = cursor.getString(0)!!,
            taskId = cursor.getString(1)!!,
            taskTitle = cursor.getString(2) ?: "Journal",
            text = text,
            completedAt = cursor.getString(4) ?: "",
        )
    }
}
