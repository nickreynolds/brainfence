package dev.brainfence.domain.blocking

import dev.brainfence.domain.model.BlockingRule
import dev.brainfence.domain.model.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class BlockingEvaluatorTest {

    private val zone = ZoneId.of("America/New_York")

    private fun instant(hour: Int, minute: Int = 0) =
        LocalDate.parse("2026-04-16").atTime(hour, minute).atZone(zone).toInstant()

    private fun taskWithWindow(
        id: String = "task-1",
        completedToday: Boolean = false,
        availableFrom: String? = "07:00",
        dueAt: String? = "10:00",
    ) = Task(
        id = id,
        userId = "user-1",
        title = "Morning routine",
        description = null,
        taskType = "habit",
        status = "active",
        recurrenceType = "daily",
        recurrenceConfig = "{}",
        verificationType = "manual",
        verificationConfig = "{}",
        tags = "[]",
        groupId = null,
        sortOrder = 0,
        isBlockingCondition = true,
        blockingRuleIds = "[]",
        availableFrom = availableFrom,
        dueAt = dueAt,
        createdAt = "2026-04-01T00:00:00Z",
        updatedAt = "2026-04-01T00:00:00Z",
        completedToday = completedToday,
        lastCompletionAt = null,
    )

    private fun manualTask(
        id: String = "task-2",
        completedToday: Boolean = false,
    ) = Task(
        id = id,
        userId = "user-1",
        title = "Manual task",
        description = null,
        taskType = "habit",
        status = "active",
        recurrenceType = "daily",
        recurrenceConfig = "{}",
        verificationType = "manual",
        verificationConfig = "{}",
        tags = "[]",
        groupId = null,
        sortOrder = 0,
        isBlockingCondition = true,
        blockingRuleIds = "[]",
        availableFrom = null,
        dueAt = null,
        createdAt = "2026-04-01T00:00:00Z",
        updatedAt = "2026-04-01T00:00:00Z",
        completedToday = completedToday,
        lastCompletionAt = null,
    )

    private fun rule(
        conditionTaskIds: List<String> = listOf("task-1"),
        conditionLogic: String = "all",
    ) = BlockingRule(
        id = "rule-1",
        userId = "user-1",
        name = "Block social media",
        blockedApps = listOf("com.twitter.android"),
        blockedDomains = emptyList(),
        conditionTaskIds = conditionTaskIds,
        conditionLogic = conditionLogic,
        configLockHours = 24,
        pendingChanges = null,
        changesApplyAt = null,
        isActive = true,
    )

    // -----------------------------------------------------------------------
    // Task with availability window + blocking evaluator
    // -----------------------------------------------------------------------

    @Test
    fun `task before available_from does not cause blocking`() {
        val task = taskWithWindow(completedToday = false)
        val result = evaluateBlocking(
            rules = listOf(rule()),
            tasks = listOf(task),
            currentTime = instant(6, 0), // before 07:00 available_from
            timeZone = zone,
        )
        assertTrue("Apps should not be blocked before available_from", result.blockedApps.isEmpty())
    }

    @Test
    fun `task during active window does not cause blocking`() {
        val task = taskWithWindow(completedToday = false)
        val result = evaluateBlocking(
            rules = listOf(rule()),
            tasks = listOf(task),
            currentTime = instant(8, 0), // between 07:00 and 10:00
            timeZone = zone,
        )
        assertTrue("Apps should not be blocked during active window", result.blockedApps.isEmpty())
    }

    @Test
    fun `task after due_at and not completed causes blocking`() {
        val task = taskWithWindow(completedToday = false)
        val result = evaluateBlocking(
            rules = listOf(rule()),
            tasks = listOf(task),
            currentTime = instant(11, 0), // after 10:00 due_at
            timeZone = zone,
        )
        assertEquals(setOf("com.twitter.android"), result.blockedApps)
    }

    @Test
    fun `task after due_at but completed does not cause blocking`() {
        val task = taskWithWindow(completedToday = true)
        val result = evaluateBlocking(
            rules = listOf(rule()),
            tasks = listOf(task),
            currentTime = instant(11, 0),
            timeZone = zone,
        )
        assertTrue("Apps should not be blocked after completion", result.blockedApps.isEmpty())
    }

    @Test
    fun `task completed during active window does not cause blocking later`() {
        val task = taskWithWindow(completedToday = true)
        val result = evaluateBlocking(
            rules = listOf(rule()),
            tasks = listOf(task),
            currentTime = instant(15, 0), // well past due_at
            timeZone = zone,
        )
        assertTrue(result.blockedApps.isEmpty())
    }

    // -----------------------------------------------------------------------
    // Mixed conditions: timed task + manual with "all" logic
    // -----------------------------------------------------------------------

    @Test
    fun `all logic - timed task before due_at and manual incomplete - blocks`() {
        // Timed task is before due_at so its condition is met.
        // Manual task (no window) is NOT completed → condition not met → blocks.
        val timedTask = taskWithWindow(id = "task-1", completedToday = false)
        val mTask = manualTask(id = "task-2", completedToday = false)
        val r = rule(conditionTaskIds = listOf("task-1", "task-2"), conditionLogic = "all")
        val result = evaluateBlocking(
            rules = listOf(r),
            tasks = listOf(timedTask, mTask),
            currentTime = instant(8, 0), // timed task in active window
            timeZone = zone,
        )
        // Manual task is incomplete → blocks
        assertEquals(setOf("com.twitter.android"), result.blockedApps)
    }

    @Test
    fun `any logic - timed task before due_at and manual incomplete - no blocking`() {
        // With "any" logic: timed task's condition is met (before due_at) → any is satisfied
        val timedTask = taskWithWindow(id = "task-1", completedToday = false)
        val mTask = manualTask(id = "task-2", completedToday = false)
        val r = rule(conditionTaskIds = listOf("task-1", "task-2"), conditionLogic = "any")
        val result = evaluateBlocking(
            rules = listOf(r),
            tasks = listOf(timedTask, mTask),
            currentTime = instant(8, 0),
            timeZone = zone,
        )
        assertTrue("Any logic: timed task treated as met before due_at", result.blockedApps.isEmpty())
    }

    @Test
    fun `all logic - both past due_at and incomplete - blocks`() {
        val timedTask = taskWithWindow(id = "task-1", completedToday = false)
        val mTask = manualTask(id = "task-2", completedToday = false)
        val r = rule(conditionTaskIds = listOf("task-1", "task-2"), conditionLogic = "all")
        val result = evaluateBlocking(
            rules = listOf(r),
            tasks = listOf(timedTask, mTask),
            currentTime = instant(11, 0), // past due_at
            timeZone = zone,
        )
        assertEquals(setOf("com.twitter.android"), result.blockedApps)
    }
}
