package dev.brainfence.data.task

import com.powersync.db.SqlCursor
import com.powersync.PowerSyncDatabase
import dev.brainfence.domain.model.Task
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onStart
import javax.inject.Inject
import javax.inject.Singleton

private val ACTIVE_TASKS_SQL = """
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
        t.available_from,
        t.due_at,
        t.created_at,
        t.updated_at,
        CASE WHEN COUNT(tc.id) > 0 THEN 1 ELSE 0 END AS completed_today,
        (SELECT MAX(tc2.completed_at) FROM task_completions tc2 WHERE tc2.task_id = t.id) AS last_completion_at
    FROM tasks t
    LEFT JOIN task_completions tc
        ON tc.task_id = t.id
        AND date(tc.completed_at, 'localtime') = date('now', 'localtime')
    WHERE t.status = 'active'
    GROUP BY t.id
    ORDER BY t.sort_order, t.created_at
""".trimIndent()

@Singleton
class TaskRepository @Inject constructor(
    private val database: PowerSyncDatabase,
) {
    /**
     * Watches active tasks with a `completedToday` flag.
     *
     * Uses onChange on both tables to guarantee re-emission when completions
     * are inserted, since PowerSync's watch auto-detection can miss tables
     * referenced only in JOINs/subqueries.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun watchActiveTasks(): Flow<List<Task>> =
        database.onChange(tables = setOf("tasks", "task_completions"))
            .onStart { emit(emptySet()) }
            .flatMapLatest {
                flow {
                    emit(database.getAll(ACTIVE_TASKS_SQL, mapper = ::mapTask))
                }
            }

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
        sortOrder           = (cursor.getLong(12) ?: 0L).toInt(),
        isBlockingCondition = (cursor.getLong(13) ?: 0L) != 0L,
        blockingRuleIds     = cursor.getString(14) ?: "[]",
        availableFrom       = cursor.getString(15),
        dueAt               = cursor.getString(16),
        createdAt           = cursor.getString(17)!!,
        updatedAt           = cursor.getString(18)!!,
        completedToday      = (cursor.getLong(19) ?: 0L) != 0L,
        lastCompletionAt    = cursor.getString(20),
    )
}
