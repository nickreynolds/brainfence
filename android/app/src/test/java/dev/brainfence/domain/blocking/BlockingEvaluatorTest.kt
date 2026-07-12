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
        homeOnlyBlocking: Boolean = false,
        blockingDaysOfWeek: String = "[]",
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
        homeOnlyBlocking = homeOnlyBlocking,
        blockingDaysOfWeek = blockingDaysOfWeek,
        createdAt = "2026-04-01T00:00:00Z",
        updatedAt = "2026-04-01T00:00:00Z",
        completedToday = completedToday,
        lastCompletionAt = null,
    )

    private fun manualTask(
        id: String = "task-2",
        completedToday: Boolean = false,
        blockingDaysOfWeek: String = "[]",
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
        homeOnlyBlocking = false,
        blockingDaysOfWeek = blockingDaysOfWeek,
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

    // -----------------------------------------------------------------------
    // Home-only blocking
    // -----------------------------------------------------------------------

    @Test
    fun `home-only task past due_at - blocks when at home`() {
        val task = taskWithWindow(completedToday = false, homeOnlyBlocking = true)
        val result = evaluateBlocking(
            rules = listOf(rule()),
            tasks = listOf(task),
            currentTime = instant(11, 0),
            timeZone = zone,
            homePresence = HomePresence.AT_HOME,
        )
        assertEquals(setOf("com.twitter.android"), result.blockedApps)
    }

    @Test
    fun `home-only task past due_at - does not block when away`() {
        val task = taskWithWindow(completedToday = false, homeOnlyBlocking = true)
        val result = evaluateBlocking(
            rules = listOf(rule()),
            tasks = listOf(task),
            currentTime = instant(11, 0),
            timeZone = zone,
            homePresence = HomePresence.AWAY,
        )
        assertTrue("Home-only task shouldn't block when away", result.blockedApps.isEmpty())
    }

    @Test
    fun `home-only task past due_at - blocks when presence unknown`() {
        // Strict-by-default: if we don't know where the user is, keep blocking.
        val task = taskWithWindow(completedToday = false, homeOnlyBlocking = true)
        val result = evaluateBlocking(
            rules = listOf(rule()),
            tasks = listOf(task),
            currentTime = instant(11, 0),
            timeZone = zone,
            homePresence = HomePresence.UNKNOWN,
        )
        assertEquals(setOf("com.twitter.android"), result.blockedApps)
    }

    @Test
    fun `home-only task past due_at - blocks when home unconfigured`() {
        val task = taskWithWindow(completedToday = false, homeOnlyBlocking = true)
        val result = evaluateBlocking(
            rules = listOf(rule()),
            tasks = listOf(task),
            currentTime = instant(11, 0),
            timeZone = zone,
            homePresence = HomePresence.UNCONFIGURED,
        )
        assertEquals(setOf("com.twitter.android"), result.blockedApps)
    }

    @Test
    fun `non home-only task past due_at - blocks regardless of presence`() {
        val task = taskWithWindow(completedToday = false, homeOnlyBlocking = false)
        val result = evaluateBlocking(
            rules = listOf(rule()),
            tasks = listOf(task),
            currentTime = instant(11, 0),
            timeZone = zone,
            homePresence = HomePresence.AWAY,
        )
        assertEquals(
            "Tasks without the home-only flag block regardless of presence",
            setOf("com.twitter.android"),
            result.blockedApps,
        )
    }

    @Test
    fun `mixed all-logic - home-only away plus manual incomplete - still blocks via manual`() {
        // Home-only task is "met" because we're away, but the manual task is still incomplete.
        // "all" logic requires every condition met → still blocks.
        val homeTask = taskWithWindow(id = "task-1", completedToday = false, homeOnlyBlocking = true)
        val mTask = manualTask(id = "task-2", completedToday = false)
        val r = rule(conditionTaskIds = listOf("task-1", "task-2"), conditionLogic = "all")
        val result = evaluateBlocking(
            rules = listOf(r),
            tasks = listOf(homeTask, mTask),
            currentTime = instant(11, 0),
            timeZone = zone,
            homePresence = HomePresence.AWAY,
        )
        assertEquals(setOf("com.twitter.android"), result.blockedApps)
    }

    @Test
    fun `all-logic - both home-only and user away - lifts blocking`() {
        val homeTaskA = taskWithWindow(id = "task-1", completedToday = false, homeOnlyBlocking = true)
        val homeTaskB = taskWithWindow(id = "task-2", completedToday = false, homeOnlyBlocking = true)
        val r = rule(conditionTaskIds = listOf("task-1", "task-2"), conditionLogic = "all")
        val result = evaluateBlocking(
            rules = listOf(r),
            tasks = listOf(homeTaskA, homeTaskB),
            currentTime = instant(11, 0),
            timeZone = zone,
            homePresence = HomePresence.AWAY,
        )
        assertTrue(
            "Both conditions are home-only and user is away → blocking lifted",
            result.blockedApps.isEmpty(),
        )
    }

    // -----------------------------------------------------------------------
    // Unknown / dangling condition tasks — fail closed
    // -----------------------------------------------------------------------

    @Test
    fun `unknown condition task fails closed - keeps blocking`() {
        // A rule referencing a task id that isn't in the current task set (dangling
        // reference to a deleted task, or a task set that hasn't loaded yet) must
        // keep blocking rather than silently lift.
        val r = rule(conditionTaskIds = listOf("missing-task"), conditionLogic = "all")
        val result = evaluateBlocking(
            rules = listOf(r),
            tasks = emptyList(),
            currentTime = instant(11, 0),
            timeZone = zone,
        )
        assertEquals(setOf("com.twitter.android"), result.blockedApps)
    }

    @Test
    fun `any logic - unknown task but a real sibling is met - lifts blocking`() {
        // Fail-closed shouldn't overreach: with "any" logic, a genuinely met
        // sibling still satisfies the rule even though the other id is unknown.
        val metTask = taskWithWindow(id = "task-1", completedToday = true)
        val r = rule(conditionTaskIds = listOf("task-1", "ghost"), conditionLogic = "any")
        val result = evaluateBlocking(
            rules = listOf(r),
            tasks = listOf(metTask),
            currentTime = instant(11, 0),
            timeZone = zone,
        )
        assertTrue(result.blockedApps.isEmpty())
    }
}
