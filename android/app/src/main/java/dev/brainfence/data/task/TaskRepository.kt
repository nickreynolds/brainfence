package dev.brainfence.data.task

import app.cash.sqldelight.db.SqlCursor
import com.powersync.PowerSyncDatabase
import dev.brainfence.domain.model.Task
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TaskRepository @Inject constructor(
    private val database: PowerSyncDatabase,
) {
    /**
     * Watches active tasks from local SQLite, with a `completedToday` flag derived
     * from task_completions for the current calendar day (device local time).
     *
     * PowerSync re-emits whenever the underlying tables change, so the UI stays
     * up to date automatically as completions sync in.
     */
    fun watchActiveTasks(): Flow<List<Task>> = database.watch(
        sql = """
            SELECT
                t.id,
                t.user_id,
                t.title,
                t.description,
                t.task_type,
                t.status,
                t.recurrence_type,
                t.recurrence_config,
                t.verification_type,
                t.verification_config,
                t.tags,
                t.group_id,
                t.sort_order,
                t.is_blocking_condition,
                t.blocking_rule_ids,
                t.created_at,
                t.updated_at,
                CASE WHEN EXISTS (
                    SELECT 1 FROM task_completions tc
                    WHERE tc.task_id = t.id
                    AND date(tc.completed_at) = date('now', 'localtime')
                ) THEN 1 ELSE 0 END AS completed_today
            FROM tasks t
            WHERE t.status = 'active'
            ORDER BY t.sort_order, t.created_at
        """.trimIndent(),
        mapper = ::mapTask,
    )

    private fun mapTask(cursor: SqlCursor): Task = Task(
        id                  = cursor.getString(0)!!,
        userId              = cursor.getString(1)!!,
        title               = cursor.getString(2)!!,
        description         = cursor.getString(3),
        taskType            = cursor.getString(4)!!,
        status              = cursor.getString(5)!!,
        recurrenceType      = cursor.getString(6),
        recurrenceConfig    = cursor.getString(7) ?: "{}",
        verificationType    = cursor.getString(8),
        verificationConfig  = cursor.getString(9) ?: "{}",
        tags                = cursor.getString(10) ?: "[]",
        groupId             = cursor.getString(11),
        sortOrder           = cursor.getLong(12)?.toInt() ?: 0,
        isBlockingCondition = (cursor.getLong(13) ?: 0L) != 0L,
        blockingRuleIds     = cursor.getString(14) ?: "[]",
        createdAt           = cursor.getString(15)!!,
        updatedAt           = cursor.getString(16)!!,
        completedToday      = (cursor.getLong(17) ?: 0L) != 0L,
    )
}
