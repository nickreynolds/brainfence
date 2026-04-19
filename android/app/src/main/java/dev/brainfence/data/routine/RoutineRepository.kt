package dev.brainfence.data.routine

import com.powersync.PowerSyncDatabase
import com.powersync.db.SqlCursor
import dev.brainfence.data.auth.SessionRepository
import dev.brainfence.domain.model.RoutineStep
import dev.brainfence.domain.model.StepCompletion
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onStart
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class NewRoutineStep(
    val title: String,
    val stepType: String,
    val config: String,
    val supersetGroup: String?,
)

@Singleton
class RoutineRepository @Inject constructor(
    private val database: PowerSyncDatabase,
    private val sessionRepository: SessionRepository,
) {
    /** Watch routine steps for a given task, ordered by step_order. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun watchRoutineSteps(taskId: String): Flow<List<RoutineStep>> =
        database.onChange(tables = setOf("routine_steps"))
            .onStart { emit(emptySet()) }
            .flatMapLatest {
                flow {
                    emit(
                        database.getAll(
                            sql = """
                                SELECT id, task_id, title, step_order, step_type, config, superset_group, created_at
                                FROM routine_steps
                                WHERE task_id = ?
                                ORDER BY step_order
                            """.trimIndent(),
                            parameters = listOf(taskId),
                            mapper = ::mapRoutineStep,
                        )
                    )
                }
            }

    /**
     * Load step_completions from the most recent task_completion for pre-filling.
     * Returns empty list if the task has never been completed.
     */
    suspend fun getLastStepCompletions(taskId: String): List<StepCompletion> {
        val lastCompletionId = database.getOptional(
            sql = """
                SELECT id FROM task_completions
                WHERE task_id = ?
                ORDER BY completed_at DESC
                LIMIT 1
            """.trimIndent(),
            parameters = listOf(taskId),
        ) { cursor -> cursor.getString(0)!! } ?: return emptyList()

        return database.getAll(
            sql = """
                SELECT id, task_completion_id, routine_step_id, set_number, data, completed_at
                FROM step_completions
                WHERE task_completion_id = ?
                ORDER BY routine_step_id, set_number
            """.trimIndent(),
            parameters = listOf(lastCompletionId),
            mapper = ::mapStepCompletion,
        )
    }

    /**
     * Complete a routine: insert one task_completion and one step_completion per set per step.
     *
     * @param taskId The routine task being completed.
     * @param stepData Map of routineStepId to list of per-set JSON data strings.
     *                 e.g. {"step-uuid" -> ["{\"reps\":10,\"weight\":135}", "{\"reps\":8,\"weight\":135}"]}
     */
    suspend fun completeRoutine(
        taskId: String,
        stepData: Map<String, List<String>>,
    ): String {
        val userId = sessionRepository.currentUser?.id
            ?: error("completeRoutine called while not authenticated")
        val now = Instant.now().toString()
        val taskCompletionId = UUID.randomUUID().toString()

        // Insert the task_completion
        database.execute(
            sql = """
                INSERT INTO task_completions
                    (id, task_id, user_id, completed_at, occurrence_date, verification_data, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            parameters = listOf(
                taskCompletionId, taskId, userId, now, now,
                """{"method":"routine","steps":${stepData.size}}""", now,
            ),
        )

        // Insert one step_completion per set per step
        for ((stepId, sets) in stepData) {
            for ((index, data) in sets.withIndex()) {
                val scId = UUID.randomUUID().toString()
                database.execute(
                    sql = """
                        INSERT INTO step_completions
                            (id, user_id, task_completion_id, routine_step_id, set_number, data, completed_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                    parameters = listOf(scId, userId, taskCompletionId, stepId, index + 1, data, now),
                )
            }
        }

        return taskCompletionId
    }

    /**
     * Insert a single routine step into an existing routine task.
     */
    suspend fun insertSingleStep(taskId: String, step: NewRoutineStep, stepOrder: Int): String {
        val userId = sessionRepository.currentUser?.id
            ?: error("insertSingleStep called while not authenticated")
        val now = Instant.now().toString()
        val stepId = UUID.randomUUID().toString()
        database.execute(
            sql = """
                INSERT INTO routine_steps
                    (id, user_id, task_id, title, step_order, step_type, config, superset_group, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            parameters = listOf(
                stepId, userId, taskId, step.title, stepOrder, step.stepType,
                step.config, step.supersetGroup, now,
            ),
        )
        return stepId
    }

    /**
     * Delete a single routine step by ID.
     */
    suspend fun deleteStep(stepId: String) {
        database.execute(
            sql = "DELETE FROM routine_steps WHERE id = ?",
            parameters = listOf(stepId),
        )
    }

    /**
     * Batch-insert routine steps for a newly created task.
     */
    suspend fun insertRoutineSteps(
        taskId: String,
        steps: List<NewRoutineStep>,
    ) {
        val userId = sessionRepository.currentUser?.id
            ?: error("insertRoutineSteps called while not authenticated")
        val now = Instant.now().toString()
        for ((index, step) in steps.withIndex()) {
            val stepId = UUID.randomUUID().toString()
            database.execute(
                sql = """
                    INSERT INTO routine_steps
                        (id, user_id, task_id, title, step_order, step_type, config, superset_group, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                parameters = listOf(
                    stepId, userId, taskId, step.title, index, step.stepType,
                    step.config, step.supersetGroup, now,
                ),
            )
        }
    }

    private fun mapRoutineStep(cursor: SqlCursor): RoutineStep = RoutineStep(
        id             = cursor.getString(0)!!,
        taskId         = cursor.getString(1)!!,
        title          = cursor.getString(2)!!,
        stepOrder      = (cursor.getLong(3) ?: 0L).toInt(),
        stepType       = cursor.getString(4) ?: "checkbox",
        config         = cursor.getString(5) ?: "{}",
        supersetGroup  = cursor.getString(6),
        createdAt      = cursor.getString(7)!!,
    )

    private fun mapStepCompletion(cursor: SqlCursor): StepCompletion = StepCompletion(
        id               = cursor.getString(0)!!,
        taskCompletionId = cursor.getString(1)!!,
        routineStepId    = cursor.getString(2)!!,
        setNumber        = (cursor.getLong(3) ?: 1L).toInt(),
        data             = cursor.getString(4) ?: "{}",
        completedAt      = cursor.getString(5)!!,
    )
}
